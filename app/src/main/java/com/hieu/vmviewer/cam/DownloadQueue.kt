package com.hieu.vmviewer.cam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Video đã tải trong Movies/VMViewer. */
data class LocalVideo(val name: String, val uri: Uri, val size: Long, val durationMs: Long)

sealed interface Dl {
    data object Queued : Dl
    data class Running(val done: Long, val total: Long, val bytesPerSec: Long) : Dl {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Done(val uri: Uri?) : Dl
    data class Failed(val message: String) : Dl
}

/**
 * Tải lần lượt từng file (camera chỉ có 1 đường WiFi, tải song song không nhanh hơn).
 * Rớt mạng -> tiếp tục bằng HTTP Range thay vì tải lại từ đầu như app gốc.
 * Đoạn nào đã có trong cache của trình phát (đã xem) thì chép từ đĩa, chỉ tải phần còn thiếu.
 * File tải xong được đưa vào Movies/VMViewer.
 */
@OptIn(UnstableApi::class)
class DownloadQueue(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val cache: Cache? = null,
) {
    companion object {
        const val FOLDER = "VMViewer"
    }

    private val _states = MutableStateFlow<Map<String, Dl>>(emptyMap())
    val states: StateFlow<Map<String, Dl>> = _states

    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()

    /** Gọi khi hàng đợi trống (dùng để trả camera về chế độ ghi). */
    var onIdle: (() -> Unit)? = null

    val isBusy: Boolean
        get() = _states.value.values.any { it is Dl.Queued || it is Dl.Running }

    private fun set(f: CamFile, s: Dl?) = _states.update { m ->
        if (s == null) m - f.fpath else m + (f.fpath to s)
    }

    fun markExisting(names: Set<String>, files: List<CamFile>) {
        _states.update { m ->
            val add = files.filter { it.name in names && m[it.fpath] == null }
                .associate { it.fpath to (Dl.Done(null) as Dl) }
            m + add
        }
    }

    fun enqueue(f: CamFile) {
        when (_states.value[f.fpath]) {
            is Dl.Queued, is Dl.Running, is Dl.Done -> return
            else -> Unit
        }
        set(f, Dl.Queued)
        jobs[f.fpath] = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val part = download(f)
                    val uri = publish(f, part)
                    part.delete()
                    set(f, Dl.Done(uri))
                } catch (e: CancellationException) {
                    set(f, null)
                    throw e
                } catch (e: Exception) {
                    set(f, Dl.Failed(e.message ?: e.javaClass.simpleName))
                } finally {
                    jobs.remove(f.fpath)
                }
            }
            if (!isBusy) onIdle?.invoke()
        }
    }

    /** Bỏ đánh dấu "Đã tải" sau khi xoá bản trên máy. */
    fun forget(names: Set<String>) = _states.update { m ->
        m.filterNot { (fpath, s) -> s is Dl.Done && fpath.substringAfterLast('\\') in names }
    }

    /**
     * Xoá video đã tải. Trả về những file không xoá được vì không thuộc app
     * (vd. cài lại app) -> cần người dùng đồng ý qua MediaStore.createDeleteRequest.
     */
    fun deleteLocal(videos: List<LocalVideo>): List<LocalVideo> = videos.filter { v ->
        try {
            context.contentResolver.delete(v.uri, null, null)
            false
        } catch (_: SecurityException) {
            true
        }
    }

    fun cancel(f: CamFile) {
        jobs.remove(f.fpath)?.cancel()
        set(f, null)
    }

    private suspend fun download(f: CamFile): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").apply { mkdirs() }
        val part = File(dir, f.name + ".part")
        var failures = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val have = part.length()
            if (f.size > 0 && have >= f.size) return part

            // >0: số byte có sẵn trong cache từ `have`; <0: -(độ dài khoảng trống tới đoạn cache kế tiếp)
            val remaining = if (f.size > 0) f.size - have else Long.MAX_VALUE
            val cached = cache?.let { c -> runCatching { c.getCachedLength(f.url, have, remaining) }.getOrNull() }
                ?: -remaining

            try {
                if (cached > 0) {
                    copyFromCache(f, part, have, cached)
                    continue
                }
                // Chỉ tải tới chỗ cache bắt đầu (nếu có), phần sau lấy từ cache
                val rangeEnd = if (-cached < remaining) have - cached - 1 else null
                val rb = Request.Builder().url(f.url)
                if (have > 0 || rangeEnd != null) rb.header("Range", "bytes=$have-${rangeEnd ?: ""}")
                client.newCall(rb.build()).execute().use { resp ->
                    if (resp.code == 416) return part
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Không có dữ liệu")

                    // 206 = server hỗ trợ Range -> nối tiếp; 200 = gửi lại từ đầu
                    val append = have > 0 && resp.code == 206
                    var done = if (append) have else 0L
                    val total = if (f.size > 0) f.size else done + body.contentLength().coerceAtLeast(0)
                    val stopAt = if (resp.code == 206 && rangeEnd != null) rangeEnd + 1 else Long.MAX_VALUE

                    FileOutputStream(part, append).use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var lastT = System.nanoTime()
                        var lastB = done
                        set(f, Dl.Running(done, total, 0))
                        while (done < stopAt) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), stopAt - done).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            failures = 0
                            val now = System.nanoTime()
                            if (now - lastT > 400_000_000L) {
                                val bps = (done - lastB) * 1_000_000_000L / (now - lastT)
                                set(f, Dl.Running(done, total, bps))
                                lastT = now
                                lastB = done
                            }
                        }
                    }
                    if (f.size <= 0 && stopAt == Long.MAX_VALUE) return part
                }
            } catch (e: IOException) {
                failures++
                if (failures > 8) throw IOException("Mất kết nối camera (${e.message})")
                delay(1500L * failures)
            }
        }
    }

    private suspend fun copyFromCache(f: CamFile, part: File, from: Long, length: Long) {
        val src = CacheDataSource(cache!!, null)   // không có upstream: chỉ đọc cache
        src.open(DataSpec.Builder().setUri(f.url).setPosition(from).setLength(length).build())
        try {
            FileOutputStream(part, true).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = from
                val end = from + length
                while (done < end) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), end - done).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                }
                set(f, Dl.Running(done, if (f.size > 0) f.size else done, 0))
            }
        } finally {
            src.close()
        }
    }

    private fun publish(f: CamFile, part: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Không tạo được file trong thư viện")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                part.inputStream().use { it.copyTo(out, 1 shl 20) }
            } ?: throw IOException("Không ghi được file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Video đã tải, mới nhất trước (tên file chứa giờ quay). */
    fun localVideos(): List<LocalVideo> {
        val out = mutableListOf<LocalVideo>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
        )
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER%")
        runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        out += LocalVideo(
                            name = c.getString(1),
                            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                            size = c.getLong(2),
                            durationMs = c.getLong(3),
                        )
                    }
                }
        }
        return out.sortedByDescending { it.name }
    }

    /** Tên các video đã tải ở các lần trước (để đánh dấu "Đã tải"). */
    fun existingNames(): Set<String> {
        val names = mutableSetOf<String>()
        val proj = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER%")
        runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)
                ?.use { c -> while (c.moveToNext()) names += c.getString(0) }
        }
        return names
    }
}
