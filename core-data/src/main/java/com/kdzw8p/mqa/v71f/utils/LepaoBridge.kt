package com.kdzw8p.mqa.v71f.utils

import android.content.Context
import org.json.JSONObject

/**
 * 模块 App ↔ 乐跑进程（Xposed）之间的微信登录回执通道。
 *
 * 乐跑进程没有模块 App 的存储权限，只能发显式广播，所以回执落在模块自己的
 * SharedPreferences 里，界面轮询它逐步显示成功/失败，同时留一份滚动日志。
 */
object LepaoBridge {
    /**
     * 每次改 Xposed 侧登录桥都要自增，并同步 xposed 模块里的 WX_BRIDGE_VERSION。
     * 界面拿它和乐跑回报的版本比对，就能判断 LSPosed 是否真的加载了新模块。
     */
    const val VERSION = "v8"

    const val ACTION_USER = "com.kdzw8p.mqa.v71f.LEPAO_USER"
    const val ACTION_WX_RESULT = "com.kdzw8p.mqa.v71f.LEPAO_WX_RESULT"

    private const val PREFS = "lepao_bridge"
    private const val KEY_RESULT = "wx_result"
    private const val KEY_LOG = "wx_log"
    private const val MAX_LOG_LINES = 200

    data class Result(
        val status: String,
        val message: String,
        val requestId: String,
        val version: String,
        val ts: Long
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 协议上下文仅保存在模块私有目录；它不含账号会话，不进入日志或界面。 */
    fun saveRuleContext(context: Context, value: JSONObject) {
        val filtered = JSONObject()
        for (key in listOf("umid", "mobileDeviceId", "mobileModel", "mobileOsVersion", "version", "version_name")) {
            val text = value.optString(key)
            if (text.isNotBlank()) filtered.put(key, text)
        }
        if (filtered.optString("umid").isBlank() || filtered.optString("mobileDeviceId").isBlank()) return
        prefs(context).edit().putString("rule_context", filtered.toString()).apply()
    }

    fun ruleSession(context: Context, session: String): String {
        val saved = prefs(context).getString("rule_context", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return JSONObject(session).apply {
            for (key in listOf("umid", "mobileDeviceId", "mobileModel", "mobileOsVersion", "version", "version_name")) {
                if (optString(key).isBlank() && saved?.optString(key)?.isNotBlank() == true) put(key, saved.optString(key))
            }
        }.toString()
    }

    /** 乐跑侧广播进来的一条回执 */
    fun record(context: Context, status: String, message: String, requestId: String, version: String) {
        val json = JSONObject()
            .put("status", status)
            .put("message", message)
            .put("request_id", requestId)
            .put("version", version)
            .put("ts", System.currentTimeMillis())
        prefs(context).edit().putString(KEY_RESULT, json.toString()).apply()
        note(context, "乐跑[$version] $status：$message")
    }

    /** 模块侧自己写的步骤日志，和乐跑回执共用一份滚动列表 */
    fun note(context: Context, line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val old = prefs(context).getString(KEY_LOG, "").orEmpty()
        val merged = if (old.isBlank()) "$stamp $line" else "$old\n$stamp $line"
        val trimmed = merged.lines().takeLast(MAX_LOG_LINES).joinToString("\n")
        prefs(context).edit().putString(KEY_LOG, trimmed).apply()
    }

    fun read(context: Context): Result? {
        val raw = prefs(context).getString(KEY_RESULT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Result(
                status = json.optString("status"),
                message = json.optString("message"),
                requestId = json.optString("request_id"),
                version = json.optString("version"),
                ts = json.optLong("ts")
            )
        }.getOrNull()
    }

    fun log(context: Context): String = prefs(context).getString(KEY_LOG, "").orEmpty()

    fun clearLog(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }

    fun clearResult(context: Context) {
        prefs(context).edit().remove(KEY_RESULT).apply()
    }
}
