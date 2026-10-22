package com.hieu.vmviewer.cam

import android.util.Xml
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader

/** Một file trên thẻ nhớ camera, đúng theo XML của lệnh 3015. */
data class CamFile(
    val name: String,
    val fpath: String,   // ví dụ A:\VIETMAP\Movie\RO\2026_1022_125616_182.MP4
    val size: Long,
    val time: String,    // "2026/10/22 12:56:24"
    val attr: Int,
) {
    /** A:\VIETMAP\Movie\x.MP4 -> http://192.168.1.254/VIETMAP/Movie/x.MP4 */
    val url: String
        get() = CamApi.BASE + fpath.removePrefix("A:").replace('\\', '/')

    val thumbUrl: String get() = "$url?custom=1&cmd=4001"

    /** Video sự kiện (va chạm / khoá tay) nằm trong thư mục RO và không bị ghi đè. */
    val isEvent: Boolean get() = fpath.contains("\\RO\\", ignoreCase = true)

    val isVideo: Boolean
        get() = name.endsWith(".MP4", true) || name.endsWith(".MOV", true) || name.endsWith(".TS", true)

    val date: String get() = time.substringBefore(' ')
    val clock: String get() = time.substringAfter(' ', "")
}

/**
 * Giao thức HTTP Novatek mà Vietmap C61 Pro dùng (rút ra từ Vietmap REC 3.9.3).
 * Tất cả hàm đều blocking -> gọi trên Dispatchers.IO.
 */
class CamApi(private val client: OkHttpClient) {

    companion object {
        const val BASE = "http://192.168.1.254"

        const val CMD_RECORD = 2001          // par=0 dừng ghi, par=1 ghi
        const val CMD_MODE = 3001            // par=1 quay phim, par=2 xem lại
        const val CMD_ABOUT = 3012
        const val CMD_FILE_LIST = 3015
        const val CMD_HEARTBEAT = 3016
        const val CMD_DELETE = 4003          // str=FPATH

        const val MODE_RECORD = 1
        const val MODE_PLAYBACK = 2
    }

    private fun get(cmd: Int, par: Int? = null, str: String? = null): String {
        val url = BASE.toHttpUrl().newBuilder()
            .addQueryParameter("custom", "1")
            .addQueryParameter("cmd", cmd.toString())
            .apply {
                if (par != null) addQueryParameter("par", par.toString())
                if (str != null) addQueryParameter("str", str)
            }
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    /** Gửi lệnh, trả về <Status> (0 = OK, âm = lỗi). */
    fun cmd(cmd: Int, par: Int? = null, str: String? = null): Int {
        val body = get(cmd, par, str)
        return Regex("<Status>\\s*(-?\\d+)\\s*</Status>").find(body)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("Phản hồi lạ từ camera")
    }

    fun ping(): Boolean = runCatching { get(CMD_ABOUT); true }.getOrDefault(false)

    fun enterPlayback() {
        runCatching { cmd(CMD_RECORD, 0) }   // -22 nếu đang không ghi -> bỏ qua
        val st = cmd(CMD_MODE, MODE_PLAYBACK)
        if (st != 0) throw IOException("Camera không chuyển sang chế độ xem lại (status $st)")
    }

    fun resumeRecording() {
        runCatching { cmd(CMD_MODE, MODE_RECORD) }
        Thread.sleep(500)
        runCatching { cmd(CMD_RECORD, 1) }
    }

    fun heartbeat() { runCatching { get(CMD_HEARTBEAT) } }

    fun delete(file: CamFile): Int = cmd(CMD_DELETE, str = file.fpath)

    fun listFiles(): List<CamFile> = parseFiles(get(CMD_FILE_LIST))

    private fun parseFiles(xml: String): List<CamFile> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val out = mutableListOf<CamFile>()
        var cur: MutableMap<String, String>? = null
        var tag: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    if (p.name == "File") cur = mutableMapOf()
                    tag = p.name
                }
                XmlPullParser.TEXT -> {
                    val c = cur
                    val t = tag
                    if (c != null && t != null) c[t] = (c[t] ?: "") + p.text
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "File") {
                        cur?.let { m ->
                            out += CamFile(
                                name = m["NAME"].orEmpty().trim(),
                                fpath = m["FPATH"].orEmpty().trim(),
                                size = m["SIZE"]?.trim()?.toLongOrNull() ?: 0L,
                                time = m["TIME"].orEmpty().trim(),
                                attr = m["ATTR"]?.trim()?.toIntOrNull() ?: 0,
                            )
                        }
                        cur = null
                    }
                    tag = null
                }
            }
            ev = p.next()
        }
        return out.filter { it.fpath.isNotEmpty() }
    }
}
