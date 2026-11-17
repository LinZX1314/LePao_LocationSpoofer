package com.kdzw8p.mqa.v71f

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kdzw8p.mqa.v71f.utils.LepaoBridge

/**
 * 乐跑进程里的 Xposed hook 没有模块 App 的存储权限，导出的用户/会话 JSON
 * 和微信登录回执都通过广播送进来，落到模块自己的 SharedPreferences，
 * 供「功能」页无 root 读取。
 */
class LepaoUserReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LepaoBridge.ACTION_WX_RESULT) {
            LepaoBridge.record(
                context,
                intent.getStringExtra("status").orEmpty(),
                intent.getStringExtra("message").orEmpty(),
                intent.getStringExtra("request_id").orEmpty(),
                intent.getStringExtra("version").orEmpty()
            )
            return
        }
        val json = intent.getStringExtra("json") ?: return
        if (json.isBlank()) return
        runCatching { org.json.JSONObject(json).optJSONObject("rule_context") }
            .getOrNull()?.let { LepaoBridge.saveRuleContext(context, it) }
        val parsed = runCatching { org.json.JSONObject(json) }.getOrNull()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                putString(KEY_JSON, json)
                // 普通资料轮询不能覆盖本次授权专属回执，否则登录已成功但UI永远等不到对应会话。
                if (!parsed?.optString("authorization_request_id").isNullOrBlank()) {
                    putString("authorization_json", json)
                }
            }
            .apply()
    }

    companion object {
        const val ACTION = LepaoBridge.ACTION_USER
        const val PREFS = "lepao_bridge"
        const val KEY_JSON = "json"

        fun read(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null)
    }
}