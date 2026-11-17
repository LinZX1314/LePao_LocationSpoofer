package com.kdzw8p.mqa.v71f.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LepaoSocialProfilesTest {
    private fun authorized(uid: String = "123") = JSONObject().put("user_id", uid).put("nickname", "Alice")
        .put("session", JSONObject().put("user_id", uid).put("token", "fixture-token").toString())
        .put("wx_openid", "fixture-openid").put("wx_refresh_token", "fixture-refresh")

    @Test fun mergesPlaceholderIntoExistingUidWithoutLosingEditedNoteOrSession() {
        val root = JSONObject().put("Alice", JSONObject().put("user_id", "123").put("remark", "my note")
            .put("remark_edited", true).put("groups", JSONObject().put("example", false)))
            .put("微信-xxx", JSONObject().put("wx_openid", "fixture-openid").put("remark", "微信 导入"))
        val result = LepaoSocialProfiles.merge(root, "wx", "fixture-openid", authorized())
        assertEquals("Alice", result.name)
        assertEquals(1, result.profiles.length())
        val profile = result.profiles.getJSONObject(result.name)
        assertEquals("my note", profile.getString("remark"))
        assertFalse(profile.getJSONObject("groups").getBoolean("example"))
        assertEquals("fixture-openid", profile.getString("wx_openid"))
        assertEquals("123", JSONObject(profile.getString("session")).getString("user_id"))
        assertEquals(2, root.length())
    }

    @Test fun repeatedAuthorizationIsIdempotentAndRenamesOnlyLinkedPlaceholder() {
        val root = JSONObject().put("微信-xxx", JSONObject().put("wx_openid", "fixture-openid"))
            .put("other", JSONObject().put("wx_openid", "other-openid"))
        val once = LepaoSocialProfiles.merge(root, "wx", "fixture-openid", authorized())
        val twice = LepaoSocialProfiles.merge(once.profiles, "wx", "fixture-openid", authorized())
        assertEquals("Alice", twice.name)
        assertEquals(2, twice.profiles.length())
        assertTrue(twice.profiles.has("other"))
        assertFalse(twice.profiles.has("微信-xxx"))
    }

    @Test fun matchingNicknameOrOtherProviderDoesNotProveIdentity() {
        val root = JSONObject().put("Alice", JSONObject().put("user_id", "456").put("qq_openid", "fixture-openid"))
        val result = LepaoSocialProfiles.merge(root, "wx", "fixture-openid", authorized())
        assertEquals("Alice (2)", result.name)
        assertEquals("456", result.profiles.getJSONObject("Alice").getString("user_id"))
    }

    @Test fun authorizationExportMustMatchRequestAndHaveUsableHostSession() {
        val export = JSONObject().put("authorization_request_id", "request-a").put("exportedAt", 1000)
            .put("user_id", "123").put("token", "fixture-token")
        assertTrue(LepaoSocialProfiles.matchesAuthorizationExport(export, "request-a", 1000))
        assertFalse(LepaoSocialProfiles.matchesAuthorizationExport(export, "request-b", 1000))
        assertFalse(LepaoSocialProfiles.matchesAuthorizationExport(export, "request-a", 1001))
        assertFalse(LepaoSocialProfiles.matchesAuthorizationExport(export, "", 1000))
        assertFalse(LepaoSocialProfiles.matchesAuthorizationExport(JSONObject(export.toString()).put("user_id", "1"), "request-a", 1000))
        assertFalse(LepaoSocialProfiles.matchesAuthorizationExport(JSONObject(export.toString()).put("token", ""), "request-a", 1000))
    }

    @Test fun legacyOpenidSessionIsMergedButNeverKeptAsHostSession() {
        val root = JSONObject().put("微信-legacy", JSONObject().put("loginMode", "wechat")
            .put("session", JSONObject().put("openid", "fixture-openid").put("access_token", "wechat-fixture").toString())
            .put("remark", "keep me").put("remark_edited", true))
        val result = LepaoSocialProfiles.merge(root, "wx", "fixture-openid", authorized())
        assertEquals(1, result.profiles.length())
        val saved = result.profiles.getJSONObject(result.name)
        assertEquals("keep me", saved.getString("remark"))
        assertEquals("fixture-token", JSONObject(saved.getString("session")).getString("token"))
        assertFalse(JSONObject(saved.getString("session")).has("openid"))
    }

    @Test fun conflictingRealUidAndGuestResponseFailWithoutMutation() {
        val root = JSONObject().put("existing", JSONObject().put("wx_openid", "fixture-openid").put("user_id", "456"))
        try { LepaoSocialProfiles.merge(root, "wx", "fixture-openid", authorized()); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals("456", root.getJSONObject("existing").getString("user_id"))
        try { LepaoSocialProfiles.merge(JSONObject(), "wx", "fixture-openid", authorized("1")); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
