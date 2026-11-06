package com.hieu.vmviewer.cam

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Một bản dữ liệu cảnh báo trên server Vietmap: [ver] dạng "260925" (yyMMdd). */
data class MapDataRelease(val ver: String, val url: String)

/**
 * Dữ liệu cảnh báo (tốc độ / camera phạt nguội, "电子狗" trong app gốc) cho C61 Pro.
 *
 * Vietmap REC đọc danh sách bản mới nhất của từng model từ một metadata.json công khai trên
 * Dropbox, tải file về máy rồi đẩy vào camera ([CamApi.uploadMapData]). Hai bước phải tách ra:
 * khi nối WiFi camera thì máy không có Internet. [client] phải là client đi mạng có Internet
 * (xem Tiles.http), không bao giờ là App.http.
 *
 * [model] = modelName trong metadata.json. Mỗi bộ dữ liệu một [dir] riêng để giữ được cả hai
 * trên máy: nạp bộ đầy đủ ([MODEL_FULL]) mà camera chạy không ổn thì nạp lại bộ chuẩn ngay, không cần mạng.
 */
class MapData(private val dir: File, private val model: String) {

    private val file = dir.resolve(FILE)
    private val verFile = dir.resolve("ver")

    /** Bản đã tải về máy, null = chưa có. */
    fun localVersion(): String? =
        verFile.takeIf { file.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }?.ifEmpty { null }

    fun localFile(): File? = file.takeIf { it.isFile && localVersion() != null }

    fun fetchLatest(client: OkHttpClient): MapDataRelease {
        val json = client.newCall(Request.Builder().url(METADATA).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return parseMetadata(json, model) ?: throw IOException("Không thấy dữ liệu cho $model")
    }

    /** Tải vào file tạm, đủ byte mới thay bản cũ; lỗi ở bất kỳ bước nào thì bản cũ (cả file lẫn [verFile]) còn nguyên. */
    fun download(client: OkHttpClient, rel: MapDataRelease) {
        dir.mkdirs()
        val tmp = dir.resolve("$FILE.part")
        try {
            client.newCall(Request.Builder().url(rel.url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("Phản hồi rỗng")
                val n = tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                val expected = body.contentLength()
                if (n == 0L || (expected >= 0 && n != expected)) throw IOException("Tải thiếu ($n/$expected byte)")
            }
            if (!tmp.renameTo(file)) throw IOException("Không lưu được file")
        } finally {
            tmp.delete()      // đã đổi tên thì không còn gì để xoá
        }
        verFile.writeText(rel.ver)
    }

    companion object {
        /** modelName trong metadata.json; C61 Pro bản "T" (SSID VM_C61ProT) là "C61ProT". */
        const val MODEL = "C61Pro"
        /**
         * Bộ đầy đủ của L110 / TS-5K / S860… (thử nghiệm): cùng định dạng và mã hoá, nhiều gấp đôi điểm
         * (thêm biển 40 km/h và các loại C61 Pro không có). Vietmap chưa từng phát bộ này cho C61 Pro.
         */
        const val MODEL_FULL = "L110"
        const val FILE = "secrect.bin"      // đúng tên app gốc gửi lên (sic)
        const val METADATA = "https://www.dropbox.com/s/8oj0rstgwl5jzdu/metadata.json?dl=1"

        /** {"version":"11","data":[{"modelName":"C61Pro","ver":"260925","urlDb":"https://…"}, …]} */
        fun parseMetadata(json: String, model: String): MapDataRelease? {
            val entry = Regex("\\{[^{}]*\\}").findAll(json).map { it.value }
                .firstOrNull { field(it, "modelName") == model } ?: return null
            val ver = field(entry, "ver") ?: return null
            val url = field(entry, "urlDb") ?: return null
            return MapDataRelease(ver, url)
        }

        private fun field(obj: String, name: String): String? =
            Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1)?.trim()

        /**
         * So bản [a] mới hơn [b] không. Camera có thể trả "T260925" / "260925sp" / "no data"
         * (app gốc bỏ "T" và "sp" rồi so số); không có số = coi như chưa có dữ liệu.
         */
        fun isNewer(a: String?, b: String?): Boolean {
            val x = a?.filter(Char::isDigit)?.toLongOrNull() ?: return false
            val y = b?.filter(Char::isDigit)?.toLongOrNull() ?: return true
            return x > y
        }
    }
}
