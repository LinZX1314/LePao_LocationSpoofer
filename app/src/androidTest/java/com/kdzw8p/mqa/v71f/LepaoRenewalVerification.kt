package com.kdzw8p.mqa.v71f

import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.kdzw8p.mqa.v71f.utils.LepaoBridge
import com.kdzw8p.mqa.v71f.utils.LepaoReadClient
import com.kdzw8p.mqa.v71f.utils.SettingsManager
import org.json.JSONObject

/** 设备上续期一个有效非当前账号，校验新票可查询、旧导出不能回滚，不输出凭据。 */
internal fun SystemFilesInstrumentation.verifyLepaoRenewal(): Bundle {
    val output = Bundle()
    fun hostStopped() = ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand("pidof com.lptiyu.tanke")
    ).bufferedReader().use { it.readText().isBlank() }
    if (!hostStopped()) return output.apply { putString("result", "FAIL_HOST_RUNNING") }
    val settings = SettingsManager(targetContext)
    val active = settings.lepaoActiveAccount
    val global = settings.lepaoSession
    val profiles = settings.getLepaoProfiles()
    val name = profiles.keys().asSequence().firstOrNull { key ->
        val p = profiles.getJSONObject(key)
        key != active && p.optString("rule_source") == "account_server_query" &&
            p.optString("rule_error").isBlank() && runCatching {
                JSONObject(p.optString("session")).optString("refresh_token").isNotBlank()
            }.getOrDefault(false)
    } ?: return output.apply { putString("result", "NO_VALID_INACTIVE_SESSION") }
    val before = settings.getLepaoProfile(name)!!
    val previous = before.getString("session")
    try {
        val renewed = LepaoReadClient.refreshSession(LepaoBridge.ruleSession(targetContext, previous), before.optString("school_id"))
        val committed = settings.commitLepaoRenewal(name, previous, renewed)
        output.putBoolean("committed", committed)
        if (!committed) return output.apply { putString("result", "COMMIT_FAILED") }
        val current = settings.getLepaoProfile(name)!!
        val rule = LepaoReadClient.runRule(LepaoBridge.ruleSession(targetContext, current.getString("session")),
            current.optString("school_id"), current.optString("student_no"), "", android.os.Build.MODEL, android.os.Build.VERSION.RELEASE)
        val next = JSONObject(renewed)
        val old = JSONObject(previous)
        output.putBoolean("token_rotated", old.optString("token") != next.optString("token"))
        output.putBoolean("same_uid", old.optString("user_id") == next.optString("user_id"))
        // 模拟真实迟到导出：只重放测试前同一账号资料，不伪造登录票。
        settings.saveLepaoProfile(name, before)
        val retained = settings.getLepaoProfile(name)!!
        val notRolledBack = retained.optString("session") == renewed
        output.putBoolean("old_export_ignored", notRolledBack)
        output.putBoolean("current_account_unchanged", active == settings.lepaoActiveAccount && global == settings.lepaoSession)
        output.putString("completed", rule.optString("total_num"))
        output.putString("target", rule.optString("term_num"))
        output.putBoolean("host_stopped_after", hostStopped())
        output.putString("result", if (notRolledBack && active == settings.lepaoActiveAccount && global == settings.lepaoSession && hostStopped()) "PASS" else "FAIL")
    } catch (e: LepaoReadClient.Failure) {
        output.putInt("status", e.status)
        output.putString("result", "REFRESH_FAILED")
    } catch (e: Exception) {
        output.putString("error_type", e.javaClass.simpleName)
        output.putString("result", "FAIL")
    }
    return output
}
