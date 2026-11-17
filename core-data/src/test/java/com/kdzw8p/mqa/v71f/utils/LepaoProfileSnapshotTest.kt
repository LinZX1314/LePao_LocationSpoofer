package com.kdzw8p.mqa.v71f.utils

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LepaoProfileSnapshotTest {
    private fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        do { val b = (n and 127).toInt(); n = n ushr 7; out.write(if (n != 0L) b or 128 else b) } while (n != 0L)
        return out.toByteArray()
    }
    private fun text(value: String): ByteArray = value.toByteArray().let { varint(it.size.toLong()) + it }
    private fun record(key: String, value: ByteArray): ByteArray = text(key) + varint(value.size.toLong()) + value
    private fun file(vararg records: ByteArray): ByteArray {
        val data = records.fold(varint(16777215L)) { a, b -> a + b }
        val header = ByteArray(4) { (data.size ushr (8 * it)).toByte() }
        return header + data + ByteArray(32)
    }
    @Test fun `integer user id and nested unicode strings decode`() {
        val profile = LepaoProfileSnapshot.decode(file(record("user_id", varint(12345678)),
            record("user_nickname", text("测试昵称")), record("school_name", text("学校")), record("auth_num", text("00123"))))
        assertEquals("12345678", profile.userId)
        assertEquals("测试昵称", profile.nickname)
        assertEquals("学校", profile.school)
        assertEquals("00123", profile.studentNo)
    }
    @Test fun `numeric student number role and sex use varint not utf8`() {
        val profile = LepaoProfileSnapshot.decode(file(record("user_id", varint(123)),
            record("auth_num", varint(20230123)), record("role", varint(1)), record("user_sex", varint(2))))
        assertEquals("20230123", profile.studentNo)
        assertEquals("1", profile.role)
        assertEquals("2", profile.sex)
    }
    @Test fun `latest record wins and deleted value does not reveal old text`() {
        val profile = LepaoProfileSnapshot.decode(file(record("user_id", varint(123)), record("user_nickname", text("old")),
            record("user_nickname", text("new")), record("school_name", text("old school")), record("school_name", byteArrayOf())))
        assertEquals("new", profile.nickname)
        assertEquals("", profile.school)
    }
    @Test fun `credentials and unknown values are skipped without decoding`() {
        val profile = LepaoProfileSnapshot.decode(file(record("user_id", varint(123)),
            record("token", byteArrayOf(-1, -1)), record("unknown", byteArrayOf(0, 1, 2))))
        assertEquals("123", profile.userId)
        assertEquals("LepaoProfileSnapshot.Profile(personal-data=redacted)", profile.toString())
    }
    @Test fun `inactive tail is never scanned for old profile`() {
        val data = file(record("user_id", varint(2))) + record("user_id", varint(999))
        assertEquals("2", LepaoProfileSnapshot.decode(data).userId)
    }
    @Test fun `guest user id never becomes an account or revives inactive tail`() {
        val data = file(record("user_id", varint(1))) + record("user_id", varint(999))
        try { LepaoProfileSnapshot.decode(data); fail() } catch (e: LepaoProfileSnapshot.Invalid) {
            assertEquals(LepaoProfileSnapshot.Problem.NO_PROFILE, e.problem)
        }
    }
    @Test fun `deleted and zero user id report no profile`() {
        for (data in listOf(file(record("user_id", varint(1)), record("user_id", byteArrayOf())), file(record("user_id", varint(0))))) {
            try { LepaoProfileSnapshot.decode(data); fail() } catch (e: LepaoProfileSnapshot.Invalid) {
                assertEquals(LepaoProfileSnapshot.Problem.NO_PROFILE, e.problem)
            }
        }
    }
    @Test fun `run rule and avatar are decoded without breaking long values`() {
        val rule = """{"run_num":1,"total_num":3,"term_num":20,"total_distance":12345.0,"run_score":"1.5",""" +
            """"run_zone_id":864,"run_zone_name":"操场","face_img":"https://x/face.jpg",""" +
            """"user_avatar":"https://x/a.jpg","point_list":[{"cover_url":"https://x/c.jpg"}],""" +
            """"tips":"${"x".repeat(5000)}"}"""
        val profile = LepaoProfileSnapshot.decode(
            file(
                record("user_id", varint(9)),
                record("user_avatar_url", text("https://x/avatar.jpg")),
                record("run_rule", text(rule))
            )
        )
        assertEquals("https://x/avatar.jpg", profile.avatarUrl)
        assertEquals(3, profile.runCount)
        assertEquals(20, profile.totalRunCount)
        assertEquals(12345.0, profile.totalDistanceM, 0.0)
        assertEquals("1.5", profile.runScore)
        assertEquals(864L, profile.venueId)
        assertEquals("操场", profile.venueName)
        assertEquals("https://x/face.jpg", profile.faceImage)
        assertEquals("https://x/c.jpg", profile.venueCoverUrl)
    }

    @Test fun `foreign cached rule never appears under current identity`() {
        val profile = LepaoProfileSnapshot.decode(file(record("user_id", varint(456)),
            record("run_rule", text("{\"uid\":123,\"run_num\":88,\"total_num\":99}"))))
        assertEquals("", profile.runRuleJson)
    }

    @Test fun `corrupt length or truncated values fail closed`() {
        val corrupt = file(record("user_id", varint(1))); corrupt[0] = -1; corrupt[1] = -1
        for (data in listOf(byteArrayOf(1), corrupt, file(text("user_id") + byteArrayOf(10, 1)))) {
            try { LepaoProfileSnapshot.decode(data); fail() } catch (e: LepaoProfileSnapshot.Invalid) {
                assertEquals(LepaoProfileSnapshot.Problem.INVALID_FORMAT, e.problem)
            }
        }
    }
}
