package com.hieu.vmviewer.cam

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Giờ thật của một clip, lấy từ box mvhd (không phải tên file / <TIME> vốn lệch 1–2 s). */
data class ClipTime(val start: LocalDateTime, val durationMs: Long) {
    val end: LocalDateTime get() = start.plusNanos(durationMs * 1_000_000)
    val date: String get() = start.format(DATE)
    val startClock: String get() = start.format(CLOCK)
    val endClock: String get() = end.format(CLOCK)

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** Một giây GPS. Giờ là giờ địa phương (trùng dòng chữ in trên hình). */
data class GpsPoint(val time: LocalDateTime, val lat: Double, val lon: Double, val speedKmh: Int)

/** Đọc ngẫu nhiên từ một file MP4; null = chưa có (vd. đoạn đó chưa vào cache). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray?

    /** Vị trí bắt đầu đoạn liên tục đã có tới hết file (null = không có / không biết). */
    fun tailStart(): Long? = null
}

/** Chỉ đọc phần đã có trong cache của trình phát, không bao giờ gọi camera. */
@OptIn(UnstableApi::class)
class CacheBytes(private val cache: Cache, private val key: String, override val size: Long) : ByteSource {
    override fun read(pos: Long, len: Int): ByteArray? {
        if (len <= 0 || !cache.isCached(key, pos, len.toLong())) return null
        val src = CacheDataSource(cache, null)
        return runCatching {
            src.open(DataSpec.Builder().setUri(key).setPosition(pos).setLength(len.toLong()).build())
            readFully(len) { b, off, n -> src.read(b, off, n) }
        }.getOrNull().also { runCatching { src.close() } }
    }

    override fun tailStart(): Long? {
        var start = size
        for (s in cache.getCachedSpans(key).descendingSet()) {
            if (s.position + s.length == start) start = s.position
            else if (s.position + s.length < start) break
        }
        return if (start < size) start else null
    }
}

/** Video đã tải (content://). */
class LocalBytes(private val resolver: ContentResolver, private val uri: Uri, override val size: Long) : ByteSource {
    override fun read(pos: Long, len: Int): ByteArray? = runCatching {
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) if (ch.read(buf, pos + buf.position()) < 0) break
                if (buf.hasRemaining()) null else buf.array()
            }
        }
    }.getOrNull()
}

private inline fun readFully(len: Int, read: (ByteArray, Int, Int) -> Int): ByteArray? {
    val b = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = read(b, off, len - off)
        if (n < 0) return null
        off += n
    }
    return b
}

/**
 * Đủ dùng cho file Novatek: ftyp, frea, mdat, moov(mvhd, trak…, udta, `gps `).
 * GPS: box `gps ` trong moov là bảng (offset, size) u32 BE, mỗi giây 1 block
 * `freeGPS ` trong mdat; payload từ byte 18 XOR 0xAA, dạng chữ:
 * `…20261022105009…N21061659E10552422800000015` = giờ, DDMM.mmmm, DDDMM.mmmm, … km/h.
 */
object Mp4 {
    private const val MP4_EPOCH_OFFSET = 2_082_844_800L   // 1904-01-01 -> 1970-01-01
    private val GPS_TIME = Regex("""\d{14}""")
    private val GPS_POS = Regex("""([NS])(\d{8})([EW])(\d{9})\d{5}(\d{3})""")
    private val GPS_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * Lấy nguyên box moov: duyệt box cấp 1 từ đầu file, không được thì tìm trong đoạn cuối.
     * Cache trình phát hay thiếu vài trăm KB đầu (chứa header mdat) nhưng có đủ moov ở cuối.
     */
    fun findMoov(src: ByteSource): ByteArray? = walkToMoov(src) ?: moovInTail(src)

    /** moov là box cuối file: tìm "moov" mà size kéo đúng tới hết file. */
    private fun moovInTail(src: ByteSource): ByteArray? {
        val from = maxOf(src.tailStart() ?: return null, src.size - (8L shl 20))
        val buf = src.read(from, (src.size - from).toInt()) ?: return null
        var i = indexOf(buf, "moov", 4)
        while (i >= 0) {
            val box = i - 4
            if (u32(buf, box) == (buf.size - box).toLong()) return buf.copyOfRange(box, buf.size)
            i = indexOf(buf, "moov", i + 4)
        }
        return null
    }

    private fun walkToMoov(src: ByteSource): ByteArray? {
        var off = 0L
        while (off + 8 <= src.size) {
            val h = src.read(off, minOf(16L, src.size - off).toInt()) ?: return null
            var size = u32(h, 0)
            if (size == 1L && h.size >= 16) size = u64(h, 8)
            if (size == 0L) size = src.size - off
            if (size < 8) return null
            if (String(h, 4, 4, Charsets.ISO_8859_1) == "moov") {
                if (size > 8L shl 20) return null
                return src.read(off, size.toInt())
            }
            off += size
        }
        return null
    }

    /**
     * Tìm mvhd trong [buf] (cả box moov, hoặc vài trăm KB cuối file). Camera ghi
     * creation_time = lúc đóng file theo giờ địa phương -> bắt đầu = đó − duration.
     */
    fun clipTime(buf: ByteArray): ClipTime? {
        var i = indexOf(buf, "mvhd", 4)
        while (i >= 0) {
            val box = i - 4
            val size = u32(buf, box)
            val v = buf.getOrNull(i + 4)?.toInt()
            val ok = (v == 0 && size == 108L) || (v == 1 && size == 120L)
            if (ok && box + size <= buf.size) {
                val p = i + 8
                val (ct, scale, dur) = if (v == 0) Triple(u32(buf, p), u32(buf, p + 8), u32(buf, p + 12))
                else Triple(u64(buf, p), u32(buf, p + 16), u64(buf, p + 20))
                if (scale > 0 && ct > MP4_EPOCH_OFFSET) {
                    val durMs = dur * 1000 / scale
                    val end = LocalDateTime.ofEpochSecond(ct - MP4_EPOCH_OFFSET, 0, ZoneOffset.UTC)
                    return ClipTime(end.minusNanos(durMs * 1_000_000), durMs)
                }
            }
            i = indexOf(buf, "mvhd", i + 4)
        }
        return null
    }

    /** Bảng (offset, size) của các block GPS, từ box moov. Rỗng nếu clip không có GPS. */
    fun gpsIndex(moov: ByteArray): List<Pair<Long, Int>> {
        var p = 8
        while (p + 8 <= moov.size) {
            val size = u32(moov, p).toInt()
            if (size < 8 || p + size > moov.size) return emptyList()
            if (String(moov, p + 4, 4, Charsets.ISO_8859_1) == "gps ") {
                return (p + 16 until p + size - 7 step 8).map { u32(moov, it) to u32(moov, it + 4).toInt() }
            }
            p += size
        }
        return emptyList()
    }

    /** Một block `freeGPS ` (chỉ cần ~256 byte đầu). */
    fun gpsPoint(block: ByteArray): GpsPoint? {
        if (block.size < 32 || String(block, 4, 8, Charsets.ISO_8859_1) != "freeGPS ") return null
        val text = String(ByteArray(block.size - 18) { (block[it + 18].toInt() xor 0xAA).toByte() }, Charsets.ISO_8859_1)
        val t = GPS_TIME.find(text) ?: return null
        val m = GPS_POS.find(text, t.range.last + 1) ?: return null
        val (ns, lat, ew, lon, speed) = m.destructured
        val time = runCatching { LocalDateTime.parse(t.value, GPS_TIME_FMT) }.getOrNull() ?: return null
        fun dm(s: String, degDigits: Int) =
            s.substring(0, degDigits).toInt() + s.substring(degDigits).toInt() / 10_000.0 / 60.0
        return GpsPoint(
            time = time,
            lat = dm(lat, 2) * if (ns == "S") -1 else 1,
            lon = dm(lon, 3) * if (ew == "W") -1 else 1,
            speedKmh = speed.toInt(),
        )
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    private fun indexOf(b: ByteArray, s: String, from: Int): Int {
        val t = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in from.coerceAtLeast(0)..b.size - t.size) {
            for (j in t.indices) if (b[i + j] != t[j]) continue@outer
            return i
        }
        return -1
    }
}
