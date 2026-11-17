package com.kdzw8p.mqa.v71f.viewmodel

import android.os.Build
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.UUID
import androidx.lifecycle.viewModelScope
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.utils.LepaoRunData
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private val ruleRequestsInFlight = mutableSetOf<String>()
// 宿主签名通路一次只处理一个请求；但用全局锁串行会让多个失效账号排队等超时，
// 因此改为各自带短超时并发发起，收到 BUSY 时由宿主侧拒绝，不阻塞其它账号。
private const val HOST_RULE_TIMEOUT_MS = 6_000L

private suspend fun queryLepaoHostRule(
    context: Context, uid: String, session: String, schoolId: String, studentNo: String
): JSONObject {
    val id = UUID.randomUUID().toString()
    val action = "${context.packageName}.RULE_REPLY.$id"
    var reply: PendingIntent? = null
    var receiver: BroadcastReceiver? = null
    return try {
        withTimeoutOrNull(HOST_RULE_TIMEOUT_MS) {
            suspendCancellableCoroutine<JSONObject> { continuation ->
                val callback = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val json = intent?.getStringExtra("result")
                            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
                        if (LepaoRunData.matchesRuleReply(json, id, uid) && continuation.isActive) {
                            continuation.resume(json)
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(callback, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
                } else context.registerReceiver(callback, IntentFilter(action))
                receiver = callback
                val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
                reply = pending
                context.sendBroadcast(Intent("com.kdzw8p.mqa.v71f.REQUEST_RULES")
                    .setPackage("com.lptiyu.tanke").putExtra("uid", uid).putExtra("session", session)
                    .putExtra("school_id", schoolId).putExtra("student_no", studentNo)
                    .putExtra("request_id", id).putExtra("reply", pending))
            }
        } ?: JSONObject().put("error", context.getString(R.string.lepao_rules_timeout))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        JSONObject().put("error", "HOST_QUERY_FAILED")
    } finally {
        reply?.cancel()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
    }
}

/** 按档案会话纯直连优先查询，不启动或切换宿主，落库前再次校验会话与UID。 */
fun MainViewModel.refreshLepaoRules(name: String, onDone: (String) -> Unit = {}) = viewModelScope.launch {
    if (!ruleRequestsInFlight.add(name)) {
        onDone(context.getString(R.string.lepao_rules_busy))
        return@launch
    }
    try {
        val profile = settingsRepository.getLepaoProfile(name) ?: return@launch
        var session = profile.optString("session")
        val uid = profile.optString("user_id")
        val sessionUid = runCatching { JSONObject(session).optString("user_id") }.getOrDefault("")
        if (!LepaoRunData.isAccountId(uid) || uid != sessionUid) {
            onDone(context.getString(R.string.lepao_data_session_mismatch))
            return@launch
        }
        val schoolId = profile.optString("school_id").ifBlank {
            LepaoRunData.objectOrNull(profile.optString("run_rule_json"))?.optString("school_id").orEmpty()
        }
        // 默认纯 HTTP，不发广播、不启动宿主；失配时才使用已在线的宿主作兼容查询。
        suspend fun directRule(): JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.kdzw8p.mqa.v71f.utils.LepaoReadClient.runRule(
                    com.kdzw8p.mqa.v71f.utils.LepaoBridge.ruleSession(context, session),
                    schoolId, profile.optString("student_no"),
                    android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty(),
                    Build.MODEL, Build.VERSION.RELEASE)
        }
        val result = try {
            val rule = try { directRule() } catch (e: com.kdzw8p.mqa.v71f.utils.LepaoReadClient.Failure) {
                if (e.status != 101 && e.status != 102) throw e
                val renewed = renewLepaoSession(name, session) ?: throw e
                session = renewed
                directRule()
            }
            JSONObject().put("rule", rule).put("source", "direct_http")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.kdzw8p.mqa.v71f.utils.LepaoReadClient.Failure) {
            if (e.status == 101 || e.status == 0) {
                // 直连不可用时才问宿主；不排队，每个账号自己 6 秒超时
                queryLepaoHostRule(context, uid, session, schoolId, profile.optString("student_no"))
                    .put("source", "host_protocol")
            } else JSONObject().put("error", e.message).put("status", e.status)
        } catch (_: Exception) {
            JSONObject().put("error", "DIRECT_QUERY_FAILED")
        }
        result.optJSONObject("rule_context")?.let {
            com.kdzw8p.mqa.v71f.utils.LepaoBridge.saveRuleContext(context, it)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val current = settingsRepository.getLepaoProfile(name) ?: return@launch
        if (current.optString("session") != session || current.optString("user_id") != uid) return@launch
        current.put("rule_attempt_at", System.currentTimeMillis())
        val rule = result.optJSONObject("rule")
        val error = when {
            result.optString("error").isNotBlank() -> when (result.optString("error")) {
                "ACCOUNT_MISMATCH", "ACCOUNT_CHANGED", "RESPONSE_IDENTITY_MISMATCH" -> context.getString(R.string.lepao_rules_mismatch)
                "BUSY" -> context.getString(R.string.lepao_rules_busy)
                "HOST_QUERY_FAILED" -> context.getString(R.string.lepao_rules_host_error) +
                    result.optString("stage").takeIf { it in setOf("IDENTITY", "PARAMETERS", "SIGNING", "TRANSPORT", "RESPONSE", "DECRYPTION") }
                        ?.let { " [$it]" }.orEmpty()
                else -> if (result.has("status")) "${result.optInt("status")}: ${result.optString("error")}" else result.optString("error")
            }
            rule == null || !LepaoRunData.validRuleOwner(rule, uid) -> context.getString(R.string.lepao_rules_mismatch)
            else -> null
        }
        if (error != null) {
            current.put("rule_error", error)
        } else {
            current.put("run_rule_json", LepaoRunData.redacted(rule).toString())
            current.put("rule_captured_at", System.currentTimeMillis())
            current.put("rule_source", "account_server_query")
            current.put("rule_transport", result.optString("source"))
            current.remove("rule_error")
            if (rule!!.has("total_num")) current.put("server_run_count", rule.optInt("total_num"))
            if (rule.has("term_num")) current.put("server_total_run_count", rule.optInt("term_num"))
            if (rule.optString("run_zone_name").isNotBlank()) current.put("venue_name", rule.optString("run_zone_name"))
            if (rule.has("run_zone_id")) current.put("venue_id", rule.optLong("run_zone_id"))
            if (rule.optString("school_id").isNotBlank()) current.put("school_id", rule.optString("school_id"))
        }
        settingsRepository.saveLepaoProfile(name, current)
        _uiState.update { it.copy(lepaoProfilesVersion = it.lepaoProfilesVersion + 1) }
        onDone(error ?: context.getString(R.string.lepao_rules_success))
    } finally {
        ruleRequestsInFlight.remove(name)
    }
}
