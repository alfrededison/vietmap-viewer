package com.hieu.vmviewer.cam

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Lưu danh sách file lần gần nhất xuống đĩa, để mở lại app (kể cả sau khi
 * Android kill process) là thấy danh sách ngay trong lúc camera đổi chế độ.
 * Mỗi dòng: NAME \t FPATH \t SIZE \t TIME \t ATTR (không trường nào chứa tab).
 */
class FileListCache(private val file: File) {

    fun load(): List<CamFile> = runCatching { decode(file.readText()) }.getOrDefault(emptyList())

    fun save(files: List<CamFile>) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(files))
            tmp.renameTo(file)
        }
    }

    companion object {
        fun encode(files: List<CamFile>): String =
            files.joinToString("\n") { "${it.name}\t${it.fpath}\t${it.size}\t${it.time}\t${it.attr}" }

        fun decode(text: String): List<CamFile> = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5 || p[1].isEmpty()) null
            else CamFile(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3], p[4].toIntOrNull() ?: 0)
        }.toList()
    }
}

/**
 * Giờ thật của clip (đọc từ mvhd) theo tên file. File trên camera không đổi nên giữ mãi.
 * Lưu giờ kết thúc (creation_time, số giây chẵn) + duration ms; giờ bắt đầu có phần lẻ giây.
 */
class ClipTimeCache(private val file: File) {

    fun load(): Map<String, ClipTime> = runCatching {
        file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            val end = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val dur = p.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
            val start = LocalDateTime.ofEpochSecond(end, 0, ZoneOffset.UTC).minusNanos(dur * 1_000_000)
            p[0] to ClipTime(start, dur)
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    fun save(times: Map<String, ClipTime>) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(times.entries.joinToString("\n") { (k, t) ->
                "$k\t${t.end.toEpochSecond(ZoneOffset.UTC)}\t${t.durationMs}"
            })
            tmp.renameTo(file)
        }
    }
}
