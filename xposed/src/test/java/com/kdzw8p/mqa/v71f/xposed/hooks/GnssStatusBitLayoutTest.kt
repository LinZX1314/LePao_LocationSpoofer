package com.kdzw8p.mqa.v71f.xposed.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssStatusBitLayoutTest {
    @Test fun modernLayoutKeepsFlagsSeparateFromConstellationAndSvid() {
        val layout = GnssStatusBitLayout(12, 8, 1, 2, 4, 8, 16)
        val packed = layout.encode(22, 5, true, true, true)
        assertEquals(22, packed ushr 12)
        assertEquals(5, (packed ushr 8) and 15)
        assertEquals(31, packed and 31)
    }

    @Test fun legacyLayoutUsesItsOwnWidthsAndDoesNotAdvertiseMissingArrays() {
        val layout = GnssStatusBitLayout(8, 4, 1, 2, 4, 8, 0)
        val packed = layout.encode(7, 3, false, false, false)
        assertEquals(7, packed ushr 8)
        assertEquals(3, (packed ushr 4) and 15)
        assertEquals(3, packed and 15)
    }
}
