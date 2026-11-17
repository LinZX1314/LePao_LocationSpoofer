package com.kdzw8p.mqa.v71f.settings

import com.kdzw8p.mqa.v71f.data.model.AppState
import com.kdzw8p.mqa.v71f.data.model.RouteRunMode
import com.kdzw8p.mqa.v71f.data.model.SimMode
import com.kdzw8p.mqa.v71f.viewmodel.LepaoProfileRow
import org.junit.Assert.*
import org.junit.Test

class LocalProfileAndDefaultsTest {
    private fun profile(session: String = "", count: Int = 0, target: Int = 0) = LepaoProfileRow(
        name = "local", remark = "", lat = "", lng = "", createdAt = 0,
        runCount = count, runTarget = target, session = session, userId = "123"
    )

    @Test fun `new session defaults to manual with wifi off and fixed 90 cadence`() {
        val state = AppState()
        assertEquals(RouteRunMode.MANUAL, state.routeRunMode)
        assertEquals(SimMode.RUNNING, state.routeSimMode)
        assertFalse(state.mockWifi)
        assertEquals(90, state.stepCadenceSpm)
        assertFalse(state.isAutoCadence)
    }

    @Test fun `local run counters never become server progress`() {
        assertEquals("—", profile(count = 40, target = 40).progressText)
        assertEquals("—", profile(count = 0, target = 0).progressText)
    }

    @Test fun `complete json means local structure only not authenticated login`() {
        val row = profile(session = "{\"user_id\":\"123\",\"token\":\"test-placeholder-not-a-real-token\"}", count = 40, target = 40)
        assertTrue(row.hasSession)
        assertEquals("—", row.progressText)
    }

    @Test fun `server rule uses own identity and never cumulative totals`() {
        val row = profile().copy(runRuleJson = "{\"uid\":123,\"run_num\":1,\"total_num\":15,\"term_num\":40}",
            statsJson = "{\"running_stat\":{\"total\":{\"total_valid_num\":99}}}")
        assertEquals("15", row.completedText)
        assertEquals("40", row.targetText)
        assertEquals("15 / 40", row.progressText)
        assertEquals("—", row.copy(runRuleJson = "").progressText)
        assertEquals("—", row.copy(userId = "456").progressText)
    }

    @Test fun `session identity must match profile`() {
        val row = profile(session = "{\"user_id\":\"456\",\"token\":\"fixture\"}")
        assertFalse(row.hasSession)
    }

    @Test fun `expired sessions are distinguished from network and generic failures`() {
        val row = profile(session = "{\"user_id\":\"123\",\"token\":\"fixture\"}")
        assertFalse(row.sessionExpired)
        assertFalse(row.hasCheckedData)
        assertTrue(row.copy(statsError = "102: expired").sessionExpired)
        assertTrue(row.copy(ruleError = "102: expired").sessionExpired)
        assertFalse(row.copy(statsError = "101: request failed").sessionExpired)
        assertTrue(row.copy(statsError = "0: timeout").refreshFailed)
        assertFalse(row.copy(statsError = "0: timeout").sessionExpired)
        assertTrue(row.copy(statsFetchedAt = 1).hasCheckedData)
    }

    @Test fun `today status expires at midnight and errors never imply not run`() {
        val row = profile(session = "{\"user_id\":\"123\",\"token\":\"fixture\"}")
            .copy(todayRunDay = "2026-10-25", todayRunCount = 1)
        assertEquals(1, row.runsOn("2026-10-25"))
        assertNull(row.runsOn("2026-10-26"))
        assertEquals(0, row.copy(todayRunCount = 0).runsOn("2026-10-25"))
        assertNull(row.copy(todayRunError = "timeout").runsOn("2026-10-25"))
        assertNull(row.copy(session = "").runsOn("2026-10-25"))
        assertNull(row.copy(ruleError = "102: expired").runsOn("2026-10-25"))
    }

    @Test fun `blank and invalid credentials are not marked complete`() {
        assertFalse(profile().hasSession)
        assertFalse(profile(session = "not json").hasSession)
        assertFalse(profile(session = "{}").hasSession)
        assertFalse(profile(session = "{\"user_id\":\"123\"}").hasSession)
        assertFalse(profile(session = "{\"token\":\"example\"}").hasSession)
    }
}
