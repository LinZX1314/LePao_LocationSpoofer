package com.kdzw8p.mqa.v71f.utils

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerSwayTest {

    private val start = 1_789_398_037_217L

    @Test
    fun `sway is deterministic bounded and continuous`() {
        assertEquals(RunnerSway.at(start, 3.0, 100.0), RunnerSway.at(start, 3.0, 100.0))
        var previous = 0.0
        for (i in 0..6000) {
            val sway = RunnerSway.at(start, 3.0, i * 0.1)
            assertTrue("lateral out of bounds: ${sway.lateralM}", abs(sway.lateralM) <= 2.5)
            assertTrue("bearing out of bounds: ${sway.bearingOffsetDeg}", abs(sway.bearingOffsetDeg) <= 8.0)
            if (i > 0) {
                assertTrue("jump at t=${i * 0.1}s", abs(sway.lateralM - previous) < 0.4)
            }
            previous = sway.lateralM
        }
    }

    @Test
    fun `no sway when stationary and amplitude scales with speed`() {
        assertEquals(0.0, RunnerSway.at(start, 0.0, 50.0).lateralM, 0.0)
        assertEquals(0.0, RunnerSway.at(start, 0.0, 50.0).bearingOffsetDeg, 0.0)
        val walking = abs(RunnerSway.at(start, 1.4, 50.0).lateralM)
        val running = abs(RunnerSway.at(start, 3.0, 50.0).lateralM)
        assertTrue("walking sway should stay within running sway", walking <= running + 1e-9)
    }

    @Test
    fun `different sessions produce different sway curves`() {
        assertNotEquals(
            RunnerSway.at(start, 3.0, 100.0),
            RunnerSway.at(start + 987_654_321L, 3.0, 100.0)
        )
    }
}
