package com.kdzw8p.mqa.v71f.utils

import org.json.JSONObject

/** 第三方身份只用于关联；只有乐跑返回的有效UID和会话才可提交到账号列表。 */
object LepaoSocialProfiles {
    data class Merge(val profiles: JSONObject, val name: String)

    fun matchesAuthorizationExport(value: JSONObject, requestId: String, startedAt: Long): Boolean =
        requestId.isNotBlank() && value.optString("authorization_request_id") == requestId &&
            value.optLong("exportedAt") >= startedAt && LepaoRunData.isAccountId(value.optString("user_id")) &&
            value.optString("token").ifBlank { value.optString("access_token") }.isNotBlank()

    fun merge(
        profiles: JSONObject, provider: String, openId: String, authorized: JSONObject
    ): Merge {
        require(provider in setOf("wx", "qq") && openId.isNotBlank())
        val uid = authorized.optString("user_id")
        val session = JSONObject(authorized.getString("session"))
        require(LepaoRunData.isAccountId(uid) && session.optString("user_id") == uid &&
            session.optString("token").ifBlank { session.optString("access_token") }.isNotBlank())
        val root = JSONObject(profiles.toString())
        val names = root.keys().asSequence().toList()
        fun linked(profile: JSONObject): Boolean {
            if (profile.optString("${provider}_openid") == openId) return true
            val mode = if (provider == "wx") "wechat" else "qq"
            val old = LepaoRunData.objectOrNull(profile.optString("session"))
            return profile.optString("loginMode") == mode && old?.optString("openid") == openId
        }
        val identityNames = names.filter { linked(root.getJSONObject(it)) }
        require(identityNames.none {
            val other = root.getJSONObject(it).optString("user_id")
            LepaoRunData.isAccountId(other) && other != uid
        }) { "SOCIAL_IDENTITY_CONFLICT" }
        val uidNames = names.filter { root.getJSONObject(it).optString("user_id") == uid }
        val canonical = uidNames.firstOrNull() ?: identityNames.firstOrNull()
        val merged = canonical?.let { JSONObject(root.getJSONObject(it).toString()) } ?: JSONObject()
        // 相同UID优先保留原档案设置；占位档案仅补齐缺失值，用户明确编辑的备注不能丢失。
        for (name in (uidNames + identityNames).distinct()) {
            val old = root.getJSONObject(name)
            old.keys().forEach { key -> if (!merged.has(key)) merged.put(key, old.opt(key)) }
            if (!merged.optBoolean("remark_edited") && old.optBoolean("remark_edited")) {
                merged.put("remark", old.optString("remark")).put("remark_edited", true)
            }
        }
        for (key in listOf("user_id", "session", "nickname", "avatar_url", "school_id", "phone", "role")) {
            if (authorized.has(key) && (key == "session" || key == "user_id" || authorized.optString(key).isNotBlank())) {
                merged.put(key, authorized.opt(key))
            }
        }
        for (key in listOf("${provider}_openid", "${provider}_access_token", "${provider}_refresh_token")) {
            if (authorized.optString(key).isNotBlank()) merged.put(key, authorized.opt(key))
        }
        merged.put("${provider}_openid", openId)
        merged.put("loginMode", if (provider == "wx") "wechat" else "qq")
        for (key in listOf("lat", "lng", "groups", "createdAt")) {
            if (!merged.has(key) && authorized.has(key)) merged.put(key, authorized.opt(key))
        }
        for (key in listOf("stats_error", "rule_error", "stats_fetched_at", "rule_captured_at",
            "stats_attempt_at", "rule_attempt_at", "today_run_day", "today_run_count", "today_run_error", "today_run_fetched_at")) merged.remove(key)
        for (name in (uidNames + identityNames).distinct()) root.remove(name)
        val base = if (canonical != null && !canonical.startsWith("微信-") && !canonical.startsWith("QQ-")) canonical
            else merged.optString("nickname").ifBlank { uid }
        var name = base
        var suffix = 2
        while (root.has(name)) name = "$base (${suffix++})"
        root.put(name, merged)
        return Merge(root, name)
    }
}
