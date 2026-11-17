package com.kdzw8p.mqa.v71f.utils

import org.json.JSONArray
import org.json.JSONObject

/** 只读展示保留字段存在性，0 与未返回不是同一个状态。 */
object LepaoRunData {
    /** 原 App 用 1 表示未登录访客，不能把它当作可查询或可写回的账号。 */
    fun isAccountId(value: String): Boolean = (value.toLongOrNull() ?: 0L) > 1L

    fun matchesRuleReply(reply: JSONObject, requestId: String, uid: String): Boolean =
        isAccountId(uid) && requestId.isNotBlank() && reply.optString("request_id") == requestId &&
            reply.optString("uid") == uid

    fun validRuleOwner(rule: JSONObject, uid: String): Boolean =
        isAccountId(uid) && (rule.optString("uid").isBlank() || rule.optString("uid") == uid) &&
            listOf("run_num", "total_num", "run_zone_list", "running_time", "run_zone_id").any { rule.has(it) && !rule.isNull(it) }

    fun value(json: JSONObject?, key: String): String? =
        json?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

    /** 原始业务字段可展示，但会话密钥及带临时签名的资源 URL 不进入 UI 或缓存。 */
    fun redacted(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key ->
                val sensitive = Regex("(?i)(token|secret|password|cookie|authorization|signature|pfkey|appkey)").containsMatchIn(key)
                put(key, if (sensitive) "[redacted]" else redacted(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (i in 0 until value.length()) put(redacted(value.opt(i)))
        }
        is String -> if (value.startsWith("https://") || value.startsWith("http://")) {
            value.substringBefore('?').substringBefore('#')
        } else value
        else -> value
    }

    fun objectOrNull(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()
}
