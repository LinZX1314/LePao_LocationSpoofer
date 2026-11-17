package com.kdzw8p.mqa.v71f.viewmodel

import com.kdzw8p.mqa.v71f.utils.LepaoReadClient
import com.kdzw8p.mqa.v71f.utils.LepaoRunData
import com.kdzw8p.mqa.v71f.utils.LepaoTodayRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 今日记录的一次取数结果：只带数据，不落库，由调用方在统计写完之后统一提交。 */
internal data class LepaoTodayOutcome(
    val session: String,
    val uid: String,
    val error: String,
    val summary: LepaoTodayRun.Summary?
)

/**
 * 今日记录独立取数，失败不改学期统计；请求结束后核对会话，避免串号。
 * 取数与写库拆开：取数跟规则/统计并发跑，写库由 [applyLepaoTodayRun] 在统计之后合并。
 */
internal suspend fun MainViewModel.fetchLepaoTodayRun(name: String): LepaoTodayOutcome? {
    val profile = settingsRepository.getLepaoProfile(name) ?: return null
    var session = profile.optString("session")
    val uid = profile.optString("user_id")
    if (!LepaoRunData.isAccountId(uid) || runCatching { JSONObject(session).optString("user_id") }.getOrNull() != uid) return null
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
    return LepaoTodayOutcome(session, uid, error, summary)
}

/** 把取数结果合并进当前档案；会话或 UID 已变则丢弃，避免串号。 */
internal fun MainViewModel.applyLepaoTodayRun(name: String, outcome: LepaoTodayOutcome): Boolean {
    val current = settingsRepository.getLepaoProfile(name) ?: return false
    if (current.optString("session") != outcome.session || current.optString("user_id") != outcome.uid) return false
    current.put("today_run_error", outcome.error)
    outcome.summary?.let { summary ->
        current.put("today_run_day", summary.day).put("today_run_count", summary.count)
        current.put("today_run_fetched_at", System.currentTimeMillis())
    }
    settingsRepository.saveLepaoProfile(name, current)
    _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
    return true
}
