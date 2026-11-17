package com.kdzw8p.mqa.v71f.utils

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LepaoOfflineAccountTest {
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply {
            do { val b = (n and 127).toInt(); n = n ushr 7; write(if (n == 0L) b else b or 128) } while (n != 0L)
        }.toByteArray()
    }
    private fun text(value: String) = value.toByteArray().let { varint(it.size.toLong()) + it }
    private fun record(key: String, value: ByteArray) = text(key) + varint(value.size.toLong()) + value
    private fun fixture(): Pair<ByteArray, ByteArray> {
        val body = varint(16777215) + record("user_id", varint(123)) + record("token", text("old-fixture")) +
            record("run_rule", text("{\"uid\":123,\"run_num\":88}")) + record("school_name", text("old school")) +
            record("unrelated", text("preserved"))
        val data = ByteArray(4096)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size)
        body.copyInto(data, 4)
        val meta = ByteArray(4096)
        val crc = CRC32().apply { update(body) }.value.toInt()
        ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, crc); putInt(4, 4); putInt(28, body.size); putInt(32, body.size); putInt(36, crc)
        }
        return data to meta
    }
    private fun account(rule: String = "") = JSONObject().put("user_id", "456")
        .put("nickname", "target").put("school", "new school").put("run_rule_json", rule)
        .put("session", JSONObject().put("user_id", "456").put("token", "new-fixture").toString())

    @Test fun switchesSessionAndRemovesOldRuleWithoutTouchingUnrelatedRecords() {
        val (data, meta) = fixture()
        val oldSize = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val (updated, crc) = LepaoOfflineAccount.patch(data, meta, LepaoOfflineAccount.values(account()))
        assertArrayEquals(data.copyOfRange(4, oldSize + 4), updated.copyOfRange(4, oldSize + 4))
        val session = LepaoProfileSnapshot.decodeSession(updated)
        assertEquals("456", session.userId)
        assertEquals("new-fixture", session.token)
        val profile = LepaoProfileSnapshot.decode(updated)
        assertEquals("new school", profile.school)
        assertEquals("", profile.runRuleJson)
        val size = ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        assertEquals(CRC32().apply { update(updated, 4, size) }.value.toInt(), ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        LepaoOfflineAccount.patch(updated, crc, LepaoOfflineAccount.values(account()))
    }

    @Test fun targetRulePreservesRealZeroAndRejectsForeignRule() {
        val (data, meta) = fixture()
        val valid = LepaoOfflineAccount.patch(data, meta, LepaoOfflineAccount.values(account("{\"uid\":456,\"run_num\":1,\"total_num\":0,\"term_num\":40}")))
        val profile = LepaoProfileSnapshot.decode(valid.first)
        assertEquals(0, profile.runCount)
        assertEquals(40, profile.totalRunCount)
        val foreign = LepaoOfflineAccount.patch(data, meta, LepaoOfflineAccount.values(account("{\"uid\":123,\"run_num\":88}")))
        assertEquals("", LepaoProfileSnapshot.decode(foreign.first).runRuleJson)
    }

    @Test fun invalidCrcAndUnsupportedFormatFailBeforeMutation() {
        val (data, meta) = fixture()
        val original = data.copyOf()
        meta[0] = (meta[0].toInt() xor 1).toByte()
        try { LepaoOfflineAccount.patch(data, meta, LepaoOfflineAccount.values(account())); fail() } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, data)
        val (_, unknown) = fixture()
        ByteBuffer.wrap(unknown).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 5)
        try { LepaoOfflineAccount.patch(data, unknown, LepaoOfflineAccount.values(account())); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun mismatchedSessionNeverProducesWritePayload() {
        val profile = account().put("user_id", "999")
        try { LepaoOfflineAccount.values(profile); fail() } catch (_: IllegalArgumentException) { }
    }
}
