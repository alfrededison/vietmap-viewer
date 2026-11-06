package com.hieu.vmviewer.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** metadata.json rút gọn từ bản thật (2026-10-22). */
class MapDataTest {

    private val json = """
        {
          "version": "11",
          "data": [
            { "modelName": "M2", "ver": "20260928",
              "urlDb": "https://minio.vietmap.io/appadmin/VIETMAP%20REC/M2/20260928/secrect.bin?dl=1" },
            { "modelName": "L110", "ver": "260925",
              "urlDb": "https://minio.vietmap.io/appadmin/VIETMAP%20REC/L110/260925/secrect.bin?dl=1" },
            { "modelName": "C61Pro", "ver": "260925",
              "urlDb": "https://minio.vietmap.io/appadmin/VIETMAP%20REC/C61%20Pro/260925/secrect.bin?dl=1" },
            { "modelName": "C61ProT", "ver": "211211",
              "urlDb": "https://minio.vietmap.io/appadmin/VIETMAP%20REC/C61ProT/211211T/secrect.bin?dl=1" }
          ]
        }
    """.trimIndent()

    @Test fun parsesModelEntry() {
        val r = MapData.parseMetadata(json, "C61Pro")!!
        assertEquals("260925", r.ver)
        assertEquals("https://minio.vietmap.io/appadmin/VIETMAP%20REC/C61%20Pro/260925/secrect.bin?dl=1", r.url)
        assertEquals("211211", MapData.parseMetadata(json, "C61ProT")!!.ver)
        assertNull(MapData.parseMetadata(json, "C61"))
        assertEquals(
            "https://minio.vietmap.io/appadmin/VIETMAP%20REC/L110/260925/secrect.bin?dl=1",
            MapData.parseMetadata(json, MapData.MODEL_FULL)!!.url,
        )
    }

    @Test fun comparesVersions() {
        assertTrue(MapData.isNewer("260925", "250101"))
        assertTrue(MapData.isNewer("260925", "no data"))
        assertTrue(MapData.isNewer("260925", null))
        assertTrue(MapData.isNewer("260925", "T250101"))
        assertFalse(MapData.isNewer("260925", "260925sp"))
        assertFalse(MapData.isNewer(null, "260925"))
    }
}
