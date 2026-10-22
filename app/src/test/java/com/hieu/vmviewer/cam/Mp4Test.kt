package com.hieu.vmviewer.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.LocalDateTime

/** Dữ liệu dựng theo file thật 2026_1022_105010_146.MP4 (C61 Pro). */
class Mp4Test {

    private fun box(type: String, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).apply { writeInt(body.size + 8); writeBytes(type); write(body) }
        return o.toByteArray()
    }

    private fun mvhd(ct: Long, scale: Int, dur: Int): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).apply {
            writeInt(0)                       // version 0 + flags
            writeInt(ct.toInt()); writeInt(ct.toInt()); writeInt(scale); writeInt(dur)
            write(ByteArray(100 - 20))        // phần còn lại của mvhd v0 (tổng 108 byte)
        }
        return box("mvhd", o.toByteArray())
    }

    private fun gpsBlock(text: String): ByteArray {
        val payload = ByteArray(200).also { text.toByteArray(Charsets.ISO_8859_1).copyInto(it) }
        val head = byteArrayOf(0, 0, 0x80.toByte(), 0) + "freeGPS ".toByteArray() + byteArrayOf(5, 1, 0, 0, 1, 3)
        return head + ByteArray(payload.size) { (payload[it].toInt() xor 0xAA).toByte() }
    }

    private val ct = 1_792_666_269L + 2_082_844_800L   // 2026-10-22 10:51:09 (giờ địa phương)

    @Test fun clipTimeFromMvhd() {
        val t = Mp4.clipTime(ByteArray(37) + mvhd(ct, 60_000, 61 * 60_000))!!
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 50, 8), t.start)
        assertEquals("10:51:09", t.endClock)
        assertEquals(61_000L, t.durationMs)
    }

    @Test fun clipTimeIgnoresStrayMvhdBytes() {
        val junk = "xxxxmvhdyyyy".toByteArray()
        assertNull(Mp4.clipTime(junk))
    }

    @Test fun findMoovAndGpsIndex() {
        val gps = ByteArrayOutputStream().also { o ->
            DataOutputStream(o).apply { writeInt(0x101); writeInt(2); writeInt(2746336); writeInt(32768); writeInt(5281320); writeInt(32768) }
        }.toByteArray()
        val moov = box("moov", mvhd(ct, 60_000, 61 * 60_000) + box("udta", ByteArray(38)) + box("gps ", gps))
        val file = box("ftyp", ByteArray(20)) + box("frea", ByteArray(100)) + box("mdat", ByteArray(1000)) + moov
        val src = object : ByteSource {
            override val size = file.size.toLong()
            override fun read(pos: Long, len: Int) = file.copyOfRange(pos.toInt(), pos.toInt() + len)
        }
        val found = Mp4.findMoov(src)!!
        assertEquals(moov.size, found.size)
        assertEquals(listOf(2746336L to 32768, 5281320L to 32768), Mp4.gpsIndex(found))
        assertEquals("10:50:08", Mp4.clipTime(found)!!.startClock)
    }

    /** Giống cache trình phát thật: có vài KB đầu, thiếu đoạn chứa header mdat, có đủ phần cuối. */
    @Test fun findMoovWhenCacheMissesMdatHeader() {
        val moov = box("moov", mvhd(ct, 60_000, 61 * 60_000) + box("gps ", ByteArray(8 + 16)))
        val file = box("ftyp", ByteArray(20)) + box("frea", ByteArray(100)) + box("mdat", ByteArray(5000)) + moov
        val tail = file.size - moov.size - 300L      // một ít mdat + moov
        val src = object : ByteSource {
            override val size = file.size.toLong()
            override fun read(pos: Long, len: Int) =
                if (pos >= tail || pos + len <= 60) file.copyOfRange(pos.toInt(), pos.toInt() + len) else null
            override fun tailStart() = tail
        }
        assertEquals(moov.size, Mp4.findMoov(src)!!.size)
    }

    @Test fun gpsPoint() {
        val p = Mp4.gpsPoint(gpsBlock("\u0000\u0000XKZDþþ20261022105018\u000cHIEU-VF5 \u0000\u0000\u0000\u0000\u0000\u0003N21061628E10552396400000036"))!!
        assertEquals(LocalDateTime.of(2026, 10, 22, 10, 50, 18), p.time)
        assertEquals(21.10271, p.lat, 1e-5)      // khớp chữ trên hình: N:21.10271
        assertEquals(105.87327, p.lon, 1e-5)
        assertEquals(36, p.speedKmh)
    }

    @Test fun gpsPointRejectsOtherBlocks() {
        assertNull(Mp4.gpsPoint(ByteArray(64)))
    }

    /** VM_SAMPLE_MP4=/đường/dẫn/2026_1022_105010_146.MP4 ./gradlew testDebugUnitTest */
    @Test fun realFile() {
        val path = System.getenv("VM_SAMPLE_MP4")
        assumeTrue(path != null && File(path).exists())
        RandomAccessFile(path, "r").use { raf ->
            val src = object : ByteSource {
                override val size = raf.length()
                override fun read(pos: Long, len: Int) = ByteArray(len).also { raf.seek(pos); raf.readFully(it) }
            }
            val moov = Mp4.findMoov(src)!!
            val t = Mp4.clipTime(moov)!!
            val idx = Mp4.gpsIndex(moov)
            val first = Mp4.gpsPoint(src.read(idx[0].first, 256))!!
            println("start=${t.start} end=${t.end} dur=${t.durationMs} gps=${idx.size} first=$first")
            assertEquals(t.start.plusSeconds(1), first.time)   // bản ghi GPS đầu = bắt đầu + 1 s
        }
    }
}
