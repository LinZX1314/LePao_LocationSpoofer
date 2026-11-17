package com.kdzw8p.mqa.v71f.utils

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 只解码已知资料字段；不返回token、登录凭据或任意原始键值。 */
object LepaoProfileSnapshot {
    const val MAX_FILE_BYTES = 1024 * 1024
    enum class Problem { INVALID_FORMAT, NO_PROFILE }
    class Invalid(val problem: Problem) : IllegalArgumentException(problem.name)

    data class Profile(
        val userId: String,
        val nickname: String,
        val school: String,
        val studentNo: String,
        val phone: String,
        val role: String,
        val sex: String,
        /** 头像地址（user_avatar_url，缺省时取 run_rule.user_avatar） */
        val avatarUrl: String = "",
        /** 已登记的人脸照片（run_rule.face_img，可能为空串） */
        val faceImage: String = "",
        /** 本学期已跑次数 / 要求次数（run_rule.total_num / term_num） */
        val runCount: Int = 0,
        val totalRunCount: Int = 0,
        /** 累计里程（米） */
        val totalDistanceM: Double = 0.0,
        val runScore: String = "",
        /** 当前生效的跑步场地（run_rule.run_zone_*） */
        val venueId: Long = 0L,
        val venueName: String = "",
        val venueCoverUrl: String = "",
        /** 学校 id（run_rule.school_id），直登的 school_id 参数 */
        val schoolId: String = "",
        val runRuleJson: String = ""
    ) {
        override fun toString(): String = "LepaoProfileSnapshot.Profile(personal-data=redacted)"
    }

    private val stringKeys = setOf(
        "user_nickname", "user_name", "school_name", "auth_num", "user_num", "user_phone", "role", "user_sex",
        "user_avatar_url"
    )

    /** 资料解码需要的全部 key：基础资料 + 跑步前置数据（run_rule 是整段 BeforeRunResponse JSON） */
    private val profileKeys = stringKeys + "run_rule"

    /** 登录态只取会话必需的字段：昵称/手机号等资料不参与，不扩大读取面 */
    private val sessionKeys = setOf(
        "access_token", "token", "user_access_token", "refresh_token", "refresh_expire", "role"
    )

    /** 这些 key 在 MMKV 里可能是 varint 数值，也可能是字符串，需要看解码位置判断 */
    private val numericKeys = setOf("auth_num", "role", "user_sex", "refresh_expire")

    /** 设备上已登录乐跑时的登录态快照；token 不参与日志与 toString */
    data class Session(
        val userId: String,
        val token: String,
        val refreshToken: String,
        val refreshExpire: String,
        val role: String
    ) {
        /** 与页面手工粘贴的会话 JSON 同构；注入端按 key 分别写 long/int/string */
        fun toConfigJson(): String = org.json.JSONObject().apply {
            put("user_id", userId)
            put("token", token)
            put("access_token", token)
            if (refreshToken.isNotEmpty()) put("refresh_token", refreshToken)
            if (refreshExpire.isNotEmpty()) put("refresh_expire", refreshExpire)
            if (role.isNotEmpty()) put("role", role)
        }.toString()

        override fun toString(): String = "LepaoProfileSnapshot.Session(credentials=redacted)"
    }

    fun decode(bytes: ByteArray): Profile {
        try {
            val (userId, strings) = readAll(bytes, profileKeys)
            if (!LepaoRunData.isAccountId(userId)) throw Invalid(Problem.NO_PROFILE)
            val rule = parseRunRule(strings["run_rule"])?.takeIf { LepaoRunData.validRuleOwner(it, userId) }
            val avatar = strings["user_avatar_url"].orEmpty().ifBlank {
                rule?.optString("user_avatar", "").orEmpty()
            }
            return Profile(
                userId, strings["user_nickname"].orEmpty().ifBlank { strings["user_name"].orEmpty() },
                strings["school_name"].orEmpty(),
                strings["user_num"].orEmpty().ifBlank { strings["auth_num"].orEmpty() },
                strings["user_phone"].orEmpty(), strings["role"].orEmpty(), strings["user_sex"].orEmpty(),
                avatarUrl = avatar,
                faceImage = rule?.optString("face_img", "").orEmpty(),
                runCount = rule?.optInt("total_num", 0) ?: 0,
                totalRunCount = rule?.optInt("term_num", 0) ?: 0,
                totalDistanceM = rule?.optDouble("total_distance", 0.0) ?: 0.0,
                runScore = rule?.optString("run_score", "").orEmpty(),
                venueId = rule?.optLong("run_zone_id", 0L) ?: 0L,
                venueName = rule?.optString("run_zone_name", "").orEmpty().trim(),
                venueCoverUrl = firstCoverUrl(rule),
                schoolId = rule?.optString("school_id", "").orEmpty(),
                runRuleJson = rule?.let { LepaoRunData.redacted(it).toString() }.orEmpty()
            )
        } catch (e: Invalid) {
            throw e
        } catch (_: Exception) {
            throw Invalid(Problem.INVALID_FORMAT)
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L


    /** run_rule 是乐跑存的 BeforeRunResponse 整段 JSON；旧版本没有或格式变了都不应拖垮资料读取 */
    private fun parseRunRule(raw: String?): org.json.JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun firstCoverUrl(rule: org.json.JSONObject?): String {
        val points = rule?.optJSONArray("point_list") ?: return ""
        for (i in 0 until points.length()) {
            val url = points.optJSONObject(i)?.optString("cover_url", "").orEmpty()
            if (url.isNotBlank()) return url
        }
        return ""
    }

    /**
     * 直读登录态：user_id + token。token 键是乐跑会话，user_access_token 是微信凭据，
     * 优先 token；只有 token 缺失时才退回 user_access_token。
     */
    fun decodeSession(bytes: ByteArray): Session {
        try {
            val (userId, values) = readAll(bytes, sessionKeys)
            val token = values["token"].orEmpty()
                .ifBlank { values["access_token"].orEmpty() }
            if (!LepaoRunData.isAccountId(userId) || token.isEmpty()) throw Invalid(Problem.NO_PROFILE)
            return Session(
                userId,
                token,
                values["refresh_token"].orEmpty(),
                values["refresh_expire"].orEmpty(),
                values["role"].orEmpty()
            )
        } catch (e: Invalid) {
            throw e
        } catch (_: Exception) {
            throw Invalid(Problem.INVALID_FORMAT)
        }
    }

    /** 顺序遍历 MMKV 追加记录；同一 key 以最后一次写入为准，删除不能重新露出旧值。 */
    private fun readAll(bytes: ByteArray, keys: Set<String>): Pair<String, Map<String, String>> {
        if (bytes.size < 5 || bytes.size > MAX_FILE_BYTES) invalid()
        val size = (0..3).fold(0L) { value, i -> value or ((bytes[i].toLong() and 255) shl (8 * i)) }
        if (size < 1 || size > bytes.size - 4) invalid()
        val end = 4 + size.toInt()
        val cursor = Cursor(bytes, 4, end)
        cursor.varint() // MMKV预留的容器长度占位，不是文件活动区长度。
        val strings = HashMap<String, String>()
        var userId = ""
        while (cursor.position < end) {
            val keySize = cursor.length()
            if (keySize == 0 || keySize > 4096) invalid()
            val key = cursor.utf8(keySize)
            val valueSize = cursor.length()
            val valueEnd = cursor.position.toLong() + valueSize
            if (valueEnd > end) invalid()
            if (key == "user_id") {
                if (valueSize == 0) userId = "" else {
                    val value = Cursor(bytes, cursor.position, valueEnd.toInt())
                    val id = value.varint()
                    if (value.position != value.end || id < 0) invalid()
                    userId = id.takeIf { it > 0 }?.toString().orEmpty()
                }
            } else if (key in keys) {
                if (valueSize == 0) strings.remove(key) else {
                    val value = Cursor(bytes, cursor.position, valueEnd.toInt())
                    val numberOrLength = value.varint()
                    val text = if (key in numericKeys && value.position == value.end) {
                        if (numberOrLength < 0) invalid()
                        numberOrLength.toString()
                    } else {
                        if (numberOrLength < 0 || numberOrLength > value.end - value.position) invalid()
                        value.utf8(numberOrLength.toInt())
                    }
                    if (value.position != value.end || text.length > MAX_VALUE_CHARS || text.any(Char::isISOControl)) invalid()
                    strings[key] = text
                }
            }
            cursor.position = valueEnd.toInt()
        }
        return userId to strings
    }

    private fun invalid(): Nothing = throw Invalid(Problem.INVALID_FORMAT)

    /** 单个值的字符数上限：run_rule 是一整段 beforeRun JSON，比普通资料字段大得多 */
    private const val MAX_VALUE_CHARS = 65536

    private class Cursor(val bytes: ByteArray, var position: Int, val end: Int) {
        fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                if (position >= end) invalid()
                val b = bytes[position++].toInt() and 255
                if (index == 9 && b > 1) invalid()
                result = result or ((b and 127).toLong() shl (index * 7))
                if (b < 128) return result
            }
            invalid()
        }

        fun length(): Int {
            val value = varint()
            if (value < 0 || value > end - position) invalid()
            return value.toInt()
        }

        fun utf8(length: Int): String {
            if (length < 0 || length > end - position) invalid()
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, position, length)).toString()
            position += length
            return decoded
        }
    }
}
