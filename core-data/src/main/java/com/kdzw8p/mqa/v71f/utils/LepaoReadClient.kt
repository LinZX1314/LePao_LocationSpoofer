package com.kdzw8p.mqa.v71f.utils

import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 业务只读查询不携带模块设备指纹：模块 ANDROID_ID 不是原 App 会话绑定的设备。 */
object LepaoReadClient {
    private const val BASE = "https://api2.lptiyu.com/v3/api.php/"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    class Failure(val status: Int, message: String) : Exception(message)

    fun parameters(
        session: JSONObject, extra: Map<String, String>,
        timestamp: Long = System.currentTimeMillis() / 1000,
        nonce: String = ((System.currentTimeMillis() % 1_000_000) + 100_000).toString()
    ): Map<String, String> {
        val uid = session.optString("user_id").ifBlank { session.optString("uid") }
        val token = session.optString("token").ifBlank { session.optString("access_token") }
        require(LepaoRunData.isAccountId(uid) && token.isNotBlank()) { "Missing Lepao user_id / token" }
        // extra 只能含业务查询字段，不允许覆盖身份或重新引入设备指纹。
        require(extra.keys.all { it in setOf("visit_uid", "page", "year", "record_id", "record_uid", "year_num") })
        return LepaoApiCrypto.signed(linkedMapOf(
            "version" to "233", "ostype" to "1", "uid" to uid, "token" to token,
            "timestamp" to timestamp.toString(), "nonce" to nonce
        ).apply { putAll(extra) })
    }

    /** beforeRun 使用账号自己的会话与学校，不依赖宿主进程或当前登录账号。 */
    fun runRule(sessionJson: String, schoolId: String, studentNo: String, deviceId: String, model: String, osVersion: String): JSONObject {
        val session = JSONObject(sessionJson)
        val ts = System.currentTimeMillis() / 1000
        val uid = session.optString("user_id")
        val token = session.optString("token").ifBlank { session.optString("access_token") }
        require(LepaoRunData.isAccountId(uid) && token.isNotBlank())
        val params = linkedMapOf(
            "mobileDeviceId" to session.optString("mobileDeviceId").ifBlank { deviceId },
            "mobileModel" to session.optString("mobileModel").ifBlank { model },
            "mobileOsVersion" to session.optString("mobileOsVersion").ifBlank { osVersion },
            "version" to session.optString("version").ifBlank { "233" },
            "version_name" to session.optString("version_name").ifBlank { "4.3.3" }, "ostype" to "1",
            "timestamp" to ts.toString(),
            "nonce" to ((System.currentTimeMillis() % 1_000_000) + 100_000).toString(),
            "school_id" to schoolId.ifBlank { "0" }, "student_num" to studentNo,
            "uid" to uid, "token" to token
        )
        // 动态字段名由型号与时间决定；Base64.DEFAULT 的64列换行属于签名原文。
        val (lpName, lpValue) = if (session.optString("umid").isNotBlank())
            LepaoDirectLogin.buildLpParam(params.getValue("mobileModel"), ts.toString(), session.getString("umid"))
        else LepaoDirectLogin.buildLpParam(params.getValue("mobileModel"), ts.toString())
        params[lpName] = lpValue
        params["sign"] = LepaoApiCrypto.md5(LepaoApiCrypto.signNew(params))
        val encrypted = LepaoApiCrypto.encryptRunRequest(JSONObject(params as Map<*, *>).toString())
        if (encrypted.isBlank()) throw Failure(0, "Request encryption failed")
        return execute("Run2/beforeRunV260", mapOf("key" to encrypted)).also {
            val owner = it.optString("uid")
            if ((owner.toLongOrNull() ?: 0L) > 0L && owner != uid) throw Failure(0, "RESPONSE_IDENTITY_MISMATCH")
            it.put("uid", uid)
            if (!LepaoRunData.validRuleOwner(it, uid)) throw Failure(0, "Invalid rule response")
        }
    }

    /** 官方同账号续期；不用第三方登录凭据，不创建另一账号登录会话。 */
    fun refreshSession(sessionJson: String, schoolId: String): String {
        val session = JSONObject(sessionJson)
        val uid = session.optString("user_id")
        val access = session.optString("token").ifBlank { session.optString("access_token") }
        val refresh = session.optString("refresh_token")
        require(LepaoRunData.isAccountId(uid) && access.isNotBlank() && refresh.isNotBlank())
        val ts = (System.currentTimeMillis() / 1000).toString()
        val model = session.optString("mobileModel").ifBlank { android.os.Build.MODEL }
        val params = linkedMapOf(
            "version" to session.optString("version").ifBlank { "233" },
            "version_name" to session.optString("version_name").ifBlank { "4.3.3" },
            "mobileModel" to model, "mobileDeviceId" to session.optString("mobileDeviceId"),
            "mobileOsVersion" to session.optString("mobileOsVersion").ifBlank { android.os.Build.VERSION.RELEASE },
            "ostype" to "1", "school_id" to schoolId.ifBlank { "0" },
            "uid" to uid, "token" to access, "access_token" to access, "refresh_token" to refresh,
            "timestamp" to ts, "nonce" to ((System.currentTimeMillis() % 1_000_000) + 100_000).toString()
        )
        val (lpName, lpValue) = if (session.optString("umid").isNotBlank())
            LepaoDirectLogin.buildLpParam(model, ts, session.getString("umid"))
        else LepaoDirectLogin.buildLpParam(model, ts)
        params[lpName] = lpValue
        params["sign"] = LepaoApiCrypto.md5(LepaoApiCrypto.signNew(params))
        val data = execute("Login/RefreshToken", mapOf("key" to LepaoApiCrypto.encryptRunRequest(JSONObject(params as Map<*, *>).toString())))
        return mergeRefreshedSession(sessionJson, data)
    }

    internal fun mergeRefreshedSession(original: String, response: JSONObject): String {
        val session = JSONObject(original)
        val uid = session.optString("user_id")
        val owner = response.optString("uid")
        if (owner.isBlank() || owner != uid) throw Failure(0, "REFRESH_IDENTITY_MISMATCH")
        val token = response.optString("access_token")
        if (token.isBlank()) throw Failure(0, "INVALID_REFRESH_RESPONSE")
        session.put("token", token).put("access_token", token)
        for (field in listOf("refresh_token", "refresh_expire")) {
            if (response.optString(field).isNotBlank()) session.put(field, response.optString(field))
        }
        return session.toString()
    }

    fun request(path: String, sessionJson: String, extra: Map<String, String> = emptyMap()): JSONObject {
        require(path in setOf("User/User", "User/myZone", "Run2/allRecordList", "Run/recordDetailV270", "Student/InfoV14"))
        return execute(path, parameters(JSONObject(sessionJson), extra))
    }

    /** 身份认证资料（姓名/院系/专业/性别/入学年份）：App「我的→身份认证」页的数据源。 */
    fun identity(sessionJson: String): JSONObject = request("Student/InfoV14", sessionJson)

    /** 轨迹文件走 OSS，不参与接口签名；限制体积，避免把整段日志拉进内存。 */
    fun fetchText(url: String, maxBytes: Long = 4L * 1024 * 1024): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", "okhttp/4.9.3").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > maxBytes) return null
            body.string().takeIf { it.length <= maxBytes }
        }
    } catch (_: Exception) {
        null
    }

    private fun execute(path: String, params: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(BASE + path)
            .header("User-Agent", "okhttp/4.9.3").post(form).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Failure(0, "HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val status = json.optInt("status", -1)
            if (status != 1) throw Failure(status, json.optString("info").ifBlank { "Request failed" })
            LepaoApiCrypto.dataObject(json) ?: throw Failure(0, "Invalid response data")
        }
    }
}
