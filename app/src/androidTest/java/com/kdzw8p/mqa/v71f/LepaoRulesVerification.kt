package com.kdzw8p.mqa.v71f

import android.content.Context
import android.os.Bundle
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.lepaoProfileRows
import com.kdzw8p.mqa.v71f.viewmodel.refreshLepaoProfileStats
import com.kdzw8p.mqa.v71f.viewmodel.patchLepaoProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 在设备验证实际列表刷新、会话隔离及持久化，只输出业务数值与错误类别。 */
internal fun SystemFilesInstrumentation.verifyLepaoRules(requireHostStopped: Boolean = false): Bundle {
    val output = Bundle()
    fun hostStopped(): Boolean = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand("pidof com.lptiyu.tanke")
    ).bufferedReader().use { it.readText().isBlank() }
    val stoppedBefore = hostStopped()
    output.putBoolean("host_stopped_before", stoppedBefore)
    if (requireHostStopped && !stoppedBefore) {
        output.putString("result", "FAIL_HOST_RUNNING")
        return output
    }
    val prefs = targetContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val activeBefore = prefs.getString("lepao_active_account", "")
    val bridgePrefs = targetContext.getSharedPreferences("lepao_bridge", Context.MODE_PRIVATE)
    fun hostUid(): String = runCatching { JSONObject(bridgePrefs.getString("json", "{}").orEmpty()).optString("user_id") }.getOrDefault("")
    val uidBefore = hostUid()
    try {
        lateinit var vm: MainViewModel
        runOnMainSync { vm = org.koin.core.context.GlobalContext.get().get<MainViewModel>() }
        val rows = vm.lepaoProfileRows().filter { it.hasSession }
        val startedAt = System.currentTimeMillis()
        runBlocking(Dispatchers.Main) { rows.map { vm.refreshLepaoProfileStats(it.name) }.joinAll() }
        val reports = JSONArray()
        var successes = 0
        for (row in vm.lepaoProfileRows().filter { it.hasSession }) {
            val stored = JSONObject(prefs.getString("lepao_profiles", "{}").orEmpty()).getJSONObject(row.name)
            val rule = runCatching { JSONObject(stored.optString("run_rule_json")) }.getOrNull()
            val verified = row.ruleError.isBlank() && rule?.optString("uid") == row.userId &&
                stored.optLong("rule_captured_at") >= startedAt && stored.optString("rule_transport") == "direct_http" &&
                row.completedText != null && row.targetText != null
            if (verified) successes++
            reports.put(JSONObject().put("account", reports.length())
                .put("completed", row.completedText).put("target", row.targetText)
                .put("persisted", verified).put("transport", stored.optString("rule_transport"))
                .put("term_fields_match", rule?.optString("total_num") == row.completedText && rule?.optString("term_num") == row.targetText)
                .put("renewed", stored.optLong("session_renewed_at") >= startedAt)
                .put("today_count", row.runsOn(java.time.LocalDate.now().toString()))
                .put("today_checked", stored.optLong("today_run_fetched_at") >= startedAt)
                .put("today_error", row.todayRunError).put("error", row.ruleError))
        }
        // 备注是独立本地字段：实际保存后回读，再恢复原备注，验证过程不改变用户资料。
        val sample = vm.lepaoProfileRows().firstOrNull()
        var remarkVerified = false
        if (sample != null) {
            val settings = com.kdzw8p.mqa.v71f.utils.SettingsManager(targetContext)
            val original = settings.getLepaoProfile(sample.name)!!
            val originalSession = original.optString("session")
            try {
                runOnMainSync { vm.patchLepaoProfile(sample.name, remark = "verification-note") }
                val updated = vm.lepaoProfileRows().first { it.name == sample.name }
                remarkVerified = updated.remark == "verification-note" && updated.userId == sample.userId &&
                    settings.getLepaoProfile(sample.name)?.optString("session") == originalSession
            } finally {
                val current = settings.getLepaoProfile(sample.name)
                if (current != null) {
                    current.put("remark", original.optString("remark"))
                    if (original.has("remark_edited")) current.put("remark_edited", original.optBoolean("remark_edited"))
                    else current.remove("remark_edited")
                    settings.saveLepaoProfile(sample.name, current)
                }
            }
        }
        output.putBoolean("remark_edit_preserves_identity", remarkVerified)
        val unchanged = activeBefore == prefs.getString("lepao_active_account", "") && uidBefore == hostUid()
        output.putString("accounts", reports.toString())
        output.putInt("verified_accounts", successes)
        output.putBoolean("current_account_unchanged", unchanged)
        val stoppedAfter = hostStopped()
        output.putBoolean("host_stopped_after", stoppedAfter)
        output.putString("result", if (successes > 0 && remarkVerified && unchanged && (!requireHostStopped || stoppedAfter)) "PASS" else "FAIL")
    } catch (e: Exception) {
        output.putString("error_type", e.javaClass.simpleName)
        output.putString("result", "FAIL")
    }
    return output
}
