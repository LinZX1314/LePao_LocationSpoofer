package com.kdzw8p.mqa.v71f.viewmodel

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.kdzw8p.mqa.v71f.utils.LepaoBridge
import com.kdzw8p.mqa.v71f.utils.LepaoReadClient
import com.kdzw8p.mqa.v71f.utils.LepaoApiCrypto
import com.kdzw8p.mqa.v71f.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val renewalLocks = mutableMapOf<String, Mutex>()
private val failedRenewals = mutableMapOf<String, Pair<String, Long>>()

/** 只续期当前档案的乐跑会话。并发失效共享换票，失败冷却，不调用 quickLogin 或其它账号。 */
internal suspend fun MainViewModel.renewLepaoSession(name: String, previous: String): String? {
    val uid = runCatching { JSONObject(previous).optString("user_id") }.getOrNull() ?: return null
    val lock = renewalLocks.getOrPut(uid) { Mutex() }
    return lock.withLock {
        val profile = settingsRepository.getLepaoProfile(name) ?: return@withLock null
        if (profile.optString("user_id") != uid) return@withLock null
        val current = profile.optString("session")
        if (current != previous) return@withLock current.takeIf {
            runCatching { JSONObject(it).optString("user_id") == uid }.getOrDefault(false)
        }
        val session = JSONObject(current)
        if (session.optString("refresh_token").isBlank()) return@withLock null
        val fingerprint = LepaoApiCrypto.md5(current)
        val failure = failedRenewals[uid]
        if (failure?.first == fingerprint && System.currentTimeMillis() - failure.second < 60_000) return@withLock null
        val renewed = try {
            withContext(Dispatchers.IO) {
                LepaoReadClient.refreshSession(LepaoBridge.ruleSession(context, current), profile.optString("school_id"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LepaoReadClient.Failure) {
            failedRenewals[uid] = fingerprint to System.currentTimeMillis()
            LepaoBridge.note(context, "[续期] 服务器未续期：status=${e.status}，保留账号数据，不强制重登")
            return@withLock null
        } catch (_: Exception) {
            failedRenewals[uid] = fingerprint to System.currentTimeMillis()
            LepaoBridge.note(context, "[续期] 网络或响应校验失败，保留现有会话")
            return@withLock null
        }
        if (!SettingsManager(context).commitLepaoRenewal(name, previous, renewed)) {
            return@withLock settingsRepository.getLepaoProfile(name)?.optString("session")
                ?.takeIf { it != previous && runCatching { JSONObject(it).optString("user_id") == uid }.getOrDefault(false) }
        }
        failedRenewals.remove(uid)
        _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
        if (settingsRepository.lepaoActiveAccount == name) {
            viewModelScope.launch { locationRepository.patchConfig { } }
            // 不启动宿主；在线时以旧token作比较后仅更新同UID凭据，不清空资料或切号。
            val proof = PendingIntent.getBroadcast(context, 9021,
                Intent("${context.packageName}.RENEW_PROOF").setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.sendBroadcast(Intent("com.kdzw8p.mqa.v71f.RENEW_SESSION").setPackage("com.lptiyu.tanke")
                .putExtra("proof", proof).putExtra("session", renewed).putExtra("previous_token", session.optString("token")
                    .ifBlank { session.optString("access_token") }))
        }
        LepaoBridge.note(context, "[续期] 同账号会话已更新，重试原查询一次")
        renewed
    }
}
