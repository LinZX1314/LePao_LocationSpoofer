package com.kdzw8p.mqa.v71f.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LepaoRunDataTest {
    @Test fun ruleReplyRequiresMatchingRequestAndAccount() {
        val reply = JSONObject().put("request_id", "request-a").put("uid", "12345")
        assertTrue(LepaoRunData.matchesRuleReply(reply, "request-a", "12345"))
        assertFalse(LepaoRunData.matchesRuleReply(reply, "request-b", "12345"))
        assertFalse(LepaoRunData.matchesRuleReply(reply, "request-a", "54321"))
        assertFalse(LepaoRunData.matchesRuleReply(reply, "", "12345"))
        assertFalse(LepaoRunData.matchesRuleReply(reply, "request-a", "1"))
    }

    @Test fun rulesPreserveZeroButRejectWrongOwnerAndEmptyPayload() {
        val rule = JSONObject().put("uid", "12345").put("run_num", 0).put("total_num", 40)
        assertTrue(LepaoRunData.validRuleOwner(rule, "12345"))
        assertEquals("0", LepaoRunData.value(rule, "run_num"))
        assertFalse(LepaoRunData.validRuleOwner(rule, "54321"))
        assertFalse(LepaoRunData.validRuleOwner(JSONObject(), "12345"))
        assertFalse(LepaoRunData.validRuleOwner(JSONObject().put("run_num", JSONObject.NULL), "12345"))
        assertTrue(LepaoRunData.validRuleOwner(JSONObject().put("running_time", JSONArray()), "12345"))
    }

    @Test fun readRequestsNeverIncludeModuleDeviceFingerprint() {
        val session = JSONObject().put("user_id", "12345").put("token", "fixture-token")
        val params = LepaoReadClient.parameters(session, mapOf("visit_uid" to "12345"), 1700000000, "123456")
        assertFalse(params.containsKey("mobileDeviceId"))
        assertFalse(params.containsKey("mobileModel"))
        assertEquals("233", params["version"])
        assertEquals("12345", params["uid"])
        assertEquals("fixture-token", params["token"])
        assertEquals(LepaoApiCrypto.sign(params.filterKeys { it != "sign" }), params["sign"])
        try {
            LepaoReadClient.parameters(session, mapOf("mobileDeviceId" to "module-id"))
            fail("Device fingerprint must not enter read-only forms")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun currentHistoryFormatPreservesIdsAndFlags() {
        val json = JSONObject().put("total_num", 43).put("list", JSONArray().put(JSONObject()
            .put("id", "42987").put("uid", "12345").put("title", "fixture zone")
            .put("distance", "1.86").put("used_time", "594").put("record_status", "1")
            .put("error_type", 0).put("capture_num", -1)))
        val record = LepaoRunHistory.parsePage(json, "2026", 1).records.single()
        assertEquals(42987L, record.recordId)
        assertEquals("fixture zone", record.name)
        assertEquals("1.86", record.distance)
        assertEquals(0, JSONObject(record.rawJson).getInt("error_type"))
        assertEquals(-1, JSONObject(record.rawJson).getInt("capture_num"))
    }

    @Test fun guestAndMalformedIdsAreNotAccounts() {
        listOf("", "0", "1", "-1", "abc").forEach { assertFalse(LepaoRunData.isAccountId(it)) }
        assertTrue(LepaoRunData.isAccountId("19175649"))
    }

    @Test fun beforeRunRequestMatchesCliEncryptionAndSignatureVectors() {
        assertEquals("K0GjekOmpfUg6DfuWdFnvZ0c4MOs1v+HOtg29gD17vs=",
            LepaoApiCrypto.encryptRunRequest("{\"hello\":\"世界\",\"n\":1}"))
        val params = mapOf("a" to "1", "b" to "2", "mobileDeviceId" to "DEV")
        assertEquals("9603b77257cfe49d9cdb9348f94069cc", LepaoApiCrypto.md5(LepaoApiCrypto.signNew(params)))
    }

    @Test fun refreshResponseMustMatchAccountAndPreserveOtherCredentials() {
        val original = JSONObject().put("user_id", "123").put("token", "old-fixture")
            .put("refresh_token", "refresh-fixture").put("user_access_token", "wechat-fixture").toString()
        val response = JSONObject().put("uid", "123").put("access_token", "new-fixture")
            .put("refresh_token", "rotated-fixture").put("refresh_expire", "1890000000")
        val merged = JSONObject(LepaoReadClient.mergeRefreshedSession(original, response))
        assertEquals("new-fixture", merged.getString("token"))
        assertEquals("new-fixture", merged.getString("access_token"))
        assertEquals("rotated-fixture", merged.getString("refresh_token"))
        assertEquals("wechat-fixture", merged.getString("user_access_token"))
        try { LepaoReadClient.mergeRefreshedSession(original, response.put("uid", "456")); fail() }
        catch (e: LepaoReadClient.Failure) { assertEquals("REFRESH_IDENTITY_MISMATCH", e.message) }
        try { LepaoReadClient.mergeRefreshedSession(original, JSONObject().put("uid", "123")); fail() }
        catch (e: LepaoReadClient.Failure) { assertEquals("INVALID_REFRESH_RESPONSE", e.message) }
    }

    @Test fun absentIsNotZero() {
        val data = JSONObject().put("zero", 0).put("empty", "").put("null", JSONObject.NULL)
        assertEquals("0", LepaoRunData.value(data, "zero"))
        assertNull(LepaoRunData.value(data, "missing"))
        assertNull(LepaoRunData.value(data, "null"))
        assertNull(LepaoRunData.value(data, "empty"))
    }

    @Test fun preservesNestedVerdictsWithoutTreatingOptionsAsVerdict() {
        val detail = JSONObject()
            .put("record_status", "1")
            .put("complain_check_status", 2)
            .put("record_failed_reason", "")
            .put("complain_reason", JSONArray().put(JSONObject().put("id", 2).put("reason", "suspicion option")))
            .put("step_info", JSONObject().put("interval", 60).put("list", JSONArray().put(0)))
            .put("capture_num", -1)
        val result = LepaoRunData.redacted(detail) as JSONObject
        assertEquals("1", result.getString("record_status"))
        assertEquals(2, result.getInt("complain_check_status"))
        assertEquals("", result.getString("record_failed_reason"))
        assertEquals("suspicion option", result.getJSONArray("complain_reason").getJSONObject(0).getString("reason"))
        assertFalse(result.has("is_suspicious"))
        assertEquals(-1, result.getInt("capture_num"))
        assertEquals(0, result.getJSONObject("step_info").getJSONArray("list").getInt(0))
    }

    @Test fun redactsCredentialsAndSignedResourceUrlsRecursively() {
        val input = JSONObject().put("access_token", "fixture-token")
            .put("nested", JSONArray().put(JSONObject().put("refresh_token", "fixture-refresh")))
            .put("file", "https://example.invalid/track.txt?Signature=fixture-signature#private")
            .put("record_status", 0)
        val result = LepaoRunData.redacted(input) as JSONObject
        assertEquals("[redacted]", result.getString("access_token"))
        assertEquals("[redacted]", result.getJSONArray("nested").getJSONObject(0).getString("refresh_token"))
        assertEquals("https://example.invalid/track.txt", result.getString("file"))
        assertEquals(0, result.getInt("record_status"))
        assertFalse(result.toString().contains("fixture-token"))
        assertFalse(result.toString().contains("fixture-signature"))
    }

    @Test fun historyPreservesOwnerYearAndAdditionalFields() {
        val data = JSONObject().put("total_num", "0").put("date", "2026")
            .put("record_list", JSONArray().put(JSONObject()
                .put("record_id", 42987).put("uid", "12345")
                .put("name", "fixture venue").put("used_time", "594")
                .put("original_distance", "1.86").put("rate", "5:19")
                .put("start_time", "1777464507").put("record_status", 1)))
        val page = LepaoRunHistory.parsePage(data, "2026", 2)
        assertEquals("0", page.totalNum)
        assertEquals(2, page.number)
        assertEquals("2026", page.year)
        val record = page.records.single()
        assertEquals("12345", record.ownerId)
        assertEquals("2026", record.year)
        assertEquals(42987L, record.recordId)
        assertEquals("594", record.usedTime)
        assertEquals(1, JSONObject(record.rawJson).getInt("record_status"))
    }

    @Test fun emptyPageIsValidButMissingListIsNot() {
        assertTrue(LepaoRunHistory.parsePage(JSONObject().put("record_list", JSONArray()), "2026", 1).records.isEmpty())
        try {
            LepaoRunHistory.parsePage(JSONObject(), "2026", 1)
            fail("Missing list must not look like a successful empty history")
        } catch (_: IllegalStateException) { }
    }
}
