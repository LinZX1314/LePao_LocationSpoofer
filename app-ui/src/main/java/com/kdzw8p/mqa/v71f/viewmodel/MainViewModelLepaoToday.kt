package com.kdzw8p.mqa.v71f.viewmodel

import com.kdzw8p.mqa.v71f.utils.LepaoReadClient
import com.kdzw8p.mqa.v71f.utils.LepaoRunData
import com.kdzw8p.mqa.v71f.utils.LepaoTodayRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 今日记录独立刷新，失败不改学期统计；请求结束后再次核对会话，避免串号。 */
internal suspend fun MainViewModel.refreshLepaoTodayRun(name: String) {
    val profile = settingsRepository.getLepaoProfile(name) ?: return
    var session = profile.optString("session")
    val uid = profile.optString("user_id")
    if (!LepaoRunData.isAccountId(uid) || runCatching { JSONObject(session).optString("user_id") }.getOrNull() != uid) return
    var summary: LepaoTodayRun.Summary? = null
    var error = ""
    suspend fun fetch() = withContext(Dispatchers.IO) { LepaoTodayRun.fetch(session) }
    try {
        summary = try { fetch() } catch (e: LepaoReadClient.Failure) {
            if (e.status !in setOf(101, 102)) throw e
            val next = renewLepaoSession(name, session) ?: throw e
            session = next
            fetch()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: LepaoReadClient.Failure) {
        error = "${e.status}: history query failed"
    } catch (_: Exception) {
        error = "HISTORY_UNCONFIRMED"
    }
    val current = settingsRepository.getLepaoProfile(name) ?: return
    if (current.optString("session") != session || current.optString("user_id") != uid) return
    current.put("today_run_error", error)
    if (summary != null) {
        current.put("today_run_day", summary.day).put("today_run_count", summary.count)
        current.put("today_run_fetched_at", System.currentTimeMillis())
    }
    settingsRepository.saveLepaoProfile(name, current)
    _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
}
