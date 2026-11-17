package com.kdzw8p.mqa.v71f.utils

import org.junit.Assert.*
import org.junit.Test

class NumericSettingInputTest {
    @Test fun `decimal comma and endpoint values are accepted`() {
        assertEquals(3.5f, NumericSettingInput.parse("3,5", 0.1f, 40f, false))
        assertEquals(90f, NumericSettingInput.parse("90", 60f, 240f, true))
        assertEquals(60f, NumericSettingInput.parse("60", 60f, 240f, true))
        assertEquals(240f, NumericSettingInput.parse("240", 60f, 240f, true))
    }

    @Test fun `slider and keyboard commits match displayed precision`() {
        assertEquals(3.1f, NumericSettingInput.normalize(3.14f, 0.1f, 40f, 1))
        assertEquals(91f, NumericSettingInput.normalize(90.9f, 60f, 240f, 0))
        assertEquals(0.1f, NumericSettingInput.normalize(0.1f, 0.1f, 40f, 1))
        assertEquals(40f, NumericSettingInput.normalize(40f, 0.1f, 40f, 1))
        assertNull(NumericSettingInput.normalize(Float.NaN, 0.1f, 40f, 1))
        assertNull(NumericSettingInput.normalize(41f, 0.1f, 40f, 1))
        assertNull(NumericSettingInput.normalize(3f, 0.1f, 40f, -1))
        assertNull(NumericSettingInput.normalize(3f, 40f, 0.1f, 1))
    }

    @Test fun `bad partial and out of range inputs are not silently clamped`() {
        for (text in listOf("", ".", "1.2.3", "NaN", "Infinity", "-1", "0", "40.1")) {
            assertNull(NumericSettingInput.parse(text, 0.1f, 40f, false))
        }
        assertNull(NumericSettingInput.parse("90.5", 60f, 240f, true))
    }
}
