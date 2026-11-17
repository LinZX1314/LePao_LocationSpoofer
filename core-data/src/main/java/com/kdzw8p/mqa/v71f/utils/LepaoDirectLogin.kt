package com.kdzw8p.mqa.v71f.utils

import android.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 乐跑直登（Login/quickLoginV339）。
 *
 * 协议来自 4.7 版反编译：
 * - 公共参数由 kd.h.e() 注入：version / mobileModel / mobileDeviceId / mobileOsVersion / ostype /
 *   school_id / timestamp / nonce（每个请求都带）。
 * - 手机+验证码：type=1 + phone + code（kd/o.java:922-923）。
 * - 学校通道：type=1 + phone + app_token（免验证码；「别人一个 id 就能跑」走的这条）。
 * - 微信 / QQ：p2.f() 把 ShareSDK 的 openid + accesstoken + type=3/2 发给同一个接口，
 *   另可带 avatar_url / nick_name / face_img。
 *
 * 成功回包 data 是 UserDataEntity：uid / access_token / refresh_token / refresh_expire / img / name。
 * 这里只把结果转成会话 JSON，不打印 token。
 */
object LepaoDirectLogin {

    private const val BASE = "https://api2.lptiyu.com/v3/api.php/"
    private const val LOGIN_PATH = "Login/quickLoginV339"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    enum class Mode { SMS, APP_TOKEN, WECHAT, QQ }

    data class LoginResult(
        val userId: String,
        val token: String,
        val refreshToken: String,
        val refreshExpire: String,
        val nickname: String,
        val avatarUrl: String,
        val role: String
    ) {
        /**
         * 同时写 token / access_token / user_access_token：乐跑不同版本读取的 key 不一样，
         * 三个都写才能保证会话注入后业务请求能带上鉴权头。
         */
        fun sessionJson(): String = JSONObject().apply {
            put("user_id", userId)
            put("token", token)
            put("access_token", token)
            put("user_access_token", token)
            if (refreshToken.isNotEmpty()) put("refresh_token", refreshToken)
            if (refreshExpire.isNotEmpty()) put("refresh_expire", refreshExpire)
            if (nickname.isNotEmpty()) put("user_nickname", nickname)
            if (avatarUrl.isNotEmpty()) put("user_avatar_url", avatarUrl)
            if (role.isNotEmpty()) put("role", role)
        }.toString()
    }

    data class QqTokens(val openId: String, val accessToken: String)

    sealed class QqAuthOutcome {
        data class Success(val tokens: QqTokens) : QqAuthOutcome()
        data class Failure(val message: String) : QqAuthOutcome()
    }

    /** QQ 授权产物独立于微信；不携带或刷新微信 refresh_token。 */
    fun qqAuthUri(tokens: QqTokens): String = android.net.Uri.Builder()
        .scheme("lepao-qq").authority("login")
        .appendQueryParameter("openid", tokens.openId)
        .appendQueryParameter("at", tokens.accessToken)
        .build().toString()

    fun parseQqAuthPayload(text: String): QqAuthOutcome {
        return try {
            val raw = text.trim()
            val tokens = when {
                raw.startsWith("lepao-qq://") -> {
                    val uri = android.net.Uri.parse(raw)
                    if (uri.host != "login") return QqAuthOutcome.Failure("Invalid QQ login URI")
                    QqTokens(uri.getQueryParameter("openid").orEmpty(), uri.getQueryParameter("at").orEmpty())
                }
                raw.startsWith("{") -> {
                    val json = JSONObject(raw)
                    if (!json.optString("platform", "qq").equals("qq", ignoreCase = true)) {
                        return QqAuthOutcome.Failure("Expected QQ credentials")
                    }
                    QqTokens(json.optString("openid"), json.optString("access_token").ifBlank { json.optString("at") })
                }
                else -> return QqAuthOutcome.Failure("Expected lepao-qq:// URI or QQ JSON")
            }
            if (tokens.openId.isBlank() || tokens.accessToken.isBlank()) {
                QqAuthOutcome.Failure("Missing QQ openid / access_token")
            } else QqAuthOutcome.Success(tokens)
        } catch (_: Exception) {
            QqAuthOutcome.Failure("Invalid QQ credential payload")
        }
    }

    data class WeChatTokens(val openId: String, val accessToken: String, val refreshToken: String = "")

    sealed class WeChatCodeOutcome {
        data class Success(val tokens: WeChatTokens) : WeChatCodeOutcome()
        data class Failure(val message: String) : WeChatCodeOutcome()
    }

    /**
     * 用乐跑自己的微信开放平台凭据把 OAuth code 换成 openid + access_token。
     * appid/secret 来自 RunApplication.initShare()；只负责微信换票，不请求乐跑服务端。
     */
    fun exchangeWeChatCode(code: String): WeChatCodeOutcome {
        // 允许直接粘微信回跳的整条 URL：...?code=xxx&state=lepao
        val normalized = code.trim().let {
            if (it.contains("code=")) it.substringAfter("code=").substringBefore("&") else it
        }
        if (normalized.isBlank()) return WeChatCodeOutcome.Failure("微信 code 为空")
        val url = "https://api.weixin.qq.com/sns/oauth2/access_token" +
            "?appid=" + WECHAT_APP_ID +
            "&secret=" + WECHAT_APP_SECRET +
            "&code=" + java.net.URLEncoder.encode(normalized, "UTF-8") +
            "&grant_type=authorization_code"
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val err = json.optInt("errcode", 0)
                if (err != 0) {
                    return WeChatCodeOutcome.Failure(json.optString("errmsg").ifBlank { "微信返回错误 $err" })
                }
                val openId = json.optString("openid")
                val token = json.optString("access_token")
                if (openId.isBlank() || token.isBlank()) {
                    WeChatCodeOutcome.Failure("微信响应缺少 openid / access_token")
                } else {
                    WeChatCodeOutcome.Success(WeChatTokens(openId, token, json.optString("refresh_token")))
                }
            }
        } catch (e: Exception) {
            WeChatCodeOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 用 refresh_token 换新的 access_token：微信 access_token 只有 2 小时，
     * refresh_token 30 天有效；刷新回包会带新的 refresh_token，调用方保存回包值。
     */
    fun refreshWeChatToken(refreshToken: String): WeChatCodeOutcome {
        if (refreshToken.isBlank()) return WeChatCodeOutcome.Failure("微信 refresh_token 为空")
        val url = "https://api.weixin.qq.com/sns/oauth2/refresh_token" +
            "?appid=" + WECHAT_APP_ID +
            "&grant_type=refresh_token" +
            "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8")
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val err = json.optInt("errcode", 0)
                if (err != 0) {
                    return WeChatCodeOutcome.Failure(json.optString("errmsg").ifBlank { "微信返回错误 $err" })
                }
                val openId = json.optString("openid")
                val token = json.optString("access_token")
                if (openId.isBlank() || token.isBlank()) {
                    WeChatCodeOutcome.Failure("微信刷新响应缺少 openid / access_token")
                } else {
                    WeChatCodeOutcome.Success(
                        WeChatTokens(openId, token, json.optString("refresh_token", refreshToken))
                    )
                }
            }
        } catch (e: Exception) {
            WeChatCodeOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 授权码载体：lepao-wx://login?openid=..&at=..&rt=..（二维码/文本均可传） */
    fun weChatAuthUri(tokens: WeChatTokens): String = buildString {
        append("lepao-wx://login?openid=").append(java.net.URLEncoder.encode(tokens.openId, "UTF-8"))
        append("&at=").append(java.net.URLEncoder.encode(tokens.accessToken, "UTF-8"))
        if (tokens.refreshToken.isNotBlank()) {
            append("&rt=").append(java.net.URLEncoder.encode(tokens.refreshToken, "UTF-8"))
        }
    }

    /** 解析授权码：lepao-wx:// URI 或 {"openid":..,"access_token":..,"refresh_token":..} JSON */
    fun parseWeChatAuthPayload(text: String): WeChatCodeOutcome {
        val raw = text.trim()
        if (raw.isBlank()) return WeChatCodeOutcome.Failure("授权码为空")
        return try {
            val tokens = when {
                raw.startsWith("lepao-wx://") -> {
                    val uri = android.net.Uri.parse(raw)
                    WeChatTokens(
                        openId = uri.getQueryParameter("openid").orEmpty(),
                        accessToken = uri.getQueryParameter("at").orEmpty(),
                        refreshToken = uri.getQueryParameter("rt").orEmpty()
                    )
                }
                raw.startsWith("{") -> {
                    val json = JSONObject(raw)
                    WeChatTokens(
                        openId = json.optString("openid"),
                        accessToken = json.optString("access_token").ifBlank { json.optString("at") },
                        refreshToken = json.optString("refresh_token").ifBlank { json.optString("rt") }
                    )
                }
                else -> return WeChatCodeOutcome.Failure("不是有效的授权码（lepao-wx:// 或 JSON）")
            }
            if (tokens.openId.isBlank() || tokens.accessToken.isBlank()) {
                WeChatCodeOutcome.Failure("授权码缺少 openid / access_token")
            } else {
                WeChatCodeOutcome.Success(tokens)
            }
        } catch (e: Exception) {
            WeChatCodeOutcome.Failure("授权码解析失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private const val WECHAT_APP_ID = "wx5a2e1ff396785475"
    private const val WECHAT_APP_SECRET = "cdb20f1be2d3cac7f46664c742937a54"

    sealed class Outcome {
        data class Success(
            val result: LoginResult,
            val sessionJson: String,
            /** 微信模式用 refresh_token 刷新过凭证时，带回最新三元组供 UI 回填/重新生成授权码 */
            val refreshedWeChat: WeChatTokens? = null
        ) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    fun login(
        mode: Mode,
        schoolId: String,
        deviceId: String,
        model: String,
        osVersion: String,
        phone: String = "",
        code: String = "",
        appToken: String = "",
        openId: String = "",
        accessToken: String = "",
        nickname: String = "",
        avatarUrl: String = "",
        faceImage: String = ""
    ): Outcome {
        // nonce 必须是数字：微信凭证要按 nonce % 10 选 AES 密钥对（com.lptiyu.tanke.utils.a.g）
        val nonce = ((System.currentTimeMillis() % 900_000_000L) + 100_000_000L).toString()
        val params = LinkedHashMap<String, String>().apply {
            put("version", "4.7.0")
            put("version_name", "4.7.0")
            put("mobileModel", model)
            put("mobileDeviceId", deviceId)
            put("mobileOsVersion", osVersion)
            put("ostype", "1")
            put("school_id", schoolId)
            put("uid", "1")
            put("timestamp", (System.currentTimeMillis() / 1000).toString())
            put("nonce", nonce)
        }

        when (mode) {
            Mode.SMS -> {
                if (phone.isBlank() || code.isBlank()) return Outcome.Failure("手机号或验证码为空")
                params["phone"] = phone
                params["code"] = code
                params["type"] = "1"
            }

            Mode.APP_TOKEN -> {
                if (appToken.isBlank()) return Outcome.Failure("学校 app_token 为空")
                if (phone.isNotBlank()) params["phone"] = phone
                params["app_token"] = appToken
                params["type"] = "1"
            }

            Mode.WECHAT, Mode.QQ -> {
                if (openId.isBlank() || accessToken.isBlank()) {
                    return Outcome.Failure("${if (mode == Mode.QQ) "QQ" else "微信"} openid 或 accesstoken 为空")
                }
                // p2.f 里 openid / accesstoken 都先过 aes.g("1", nonce, value) 再发给接口
                val nonceInt = nonce.toInt()
                params["openid"] = encryptWechat(nonceInt, openId)
                params["accesstoken"] = encryptWechat(nonceInt, accessToken)
                params["type"] = if (mode == Mode.QQ) "2" else "3"
            }
        }
        if (nickname.isNotBlank()) params["nick_name"] = nickname
        if (avatarUrl.isNotBlank()) params["avatar_url"] = avatarUrl
        if (faceImage.isNotBlank()) params["face_img"] = faceImage

        // u7 组（含 quickLoginV339）：sign → 隐形参数 lpX → 整份 JSON AES 加密成 key=（协议文档 1.3/1.4/1.5）
        params["sign"] = LepaoApiCrypto.sign(params)
        val (lpName, lpValue) = buildLpParam(params["mobileModel"].orEmpty(), params["timestamp"].orEmpty())
        params[lpName] = lpValue
        val form = FormBody.Builder()
            .add("key", LepaoApiCrypto.encrypt(JSONObject(params).toString()))
            .build()

        val request = Request.Builder()
            .url(BASE + LOGIN_PATH)
            .header("User-Agent", "okhttp/4.9.3")
            .post(form)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return Outcome.Failure("HTTP ${response.code}")
                val json = JSONObject(body)
                if (json.optInt("status", -1) != 1) {
                    val message = json.optString("info").ifBlank {
                        json.optString("msg").ifBlank { body.take(180) }
                    }
                    return Outcome.Failure(message.ifBlank { "登录失败" })
                }
                val data = LepaoApiCrypto.dataObject(json)
                    ?: return Outcome.Failure("登录响应缺少 data / 解密失败")
                val uid = data.optString("uid").ifBlank { data.optString("user_id") }
                val token = data.optString("access_token").ifBlank { data.optString("token") }
                if (uid.isBlank() || token.isBlank()) return Outcome.Failure("登录响应缺少 uid / access_token")
                val result = LoginResult(
                    userId = uid,
                    token = token,
                    refreshToken = data.optString("refresh_token"),
                    refreshExpire = data.optString("refresh_expire"),
                    nickname = data.optString("name"),
                    avatarUrl = data.optString("img"),
                    role = data.optInt("role", 0).toString()
                )
                Outcome.Success(result, result.sessionJson())
            }
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * com.lptiyu.tanke.utils.a.g("1", nonce, value)：AES/CBC/PKCS5Padding，
     * key 取 rc.c.Z8[nonce % 10][0] 的前 16 字节，IV 取同下标的第二列，输出 Android Base64(DEFAULT)。
     * 线上环境 kd.o.q=2，走 Z8；旧环境 a9 不覆盖。
     */
    private fun encryptWechat(nonce: Int, value: String): String {
        val pair = WECHAT_KEY_IV[Math.floorMod(nonce, WECHAT_KEY_IV.size)]
        val key = SecretKeySpec(pair.first.toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec(pair.second.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
    }

    /** rc.c.f116b9：隐形参数 lpX 的 type2 密钥表（15 组，生产） */
    private val LPX_TABLE = arrayOf(
        "L3xP7kQ2fD9sH5jR" to "N8wB4mG6vK2rT9cE", "Z5tF8gS3dA7hJ2pQ" to "Y6rU3eW9qX4sC8vB",
        "Q2mK7jP3nL5iO9uY" to "W4sE8qR2tT5yU3iO", "D9fG3hJ7kL2pM5rN" to "B6vC8nX3mZ5dF7gH",
        "P7bN2vF9cD4mK6jA" to "S3hG5jJ8dL7fT2wQ", "H5jG8fD3sA6dF9gH" to "K7lJ2pO5iU8yT3rE",
        "X8rV3tB6yG9hN4kP" to "E2sW5qA3dZ7fC8jM", "M3dS5fG8hJ2kL7pR" to "C4tY9rU6eW3qX5sZ",
        "F6dG2hJ5kL8pM3rN" to "Z7xC5vB3nA9sD2fH", "V5bC3nM8vX4cZ6dF" to "J9kL2jH5gD3fS7dA",
        "B8vN5cM2xZ9dF3gJ" to "H4kL7jP2mQ5rT8sW", "N3mK6lJ8pO5iU2yT" to "G5hF7dS9fA3gH4jK",
        "T6rE2yU5iO8pJ3lK" to "J7kH3gF5dS8aQ2wE", "W9qE3sA6dF5gH7jK" to "R5tY2eU7iO8pJ3lK",
        "S4dF8aQ3wE6rT5yU" to "I2uO5pJ8lK3jH7gF"
    )
    private const val UMID_PROD = "4x7y0z3a6b9c2d5e8f1g4h7i0j3k6l9m"

    /** kd/k.java#m()：隐形防重放参数 lpX，表索引 = timestamp % 15 */
    internal fun buildLpParam(model: String, ts: String, umid: String = UMID_PROD): Pair<String, String> {
        val name = "lp" + (if (model.length > 1) model[1].lowercaseChar() else '0') +
            (if (ts.length > 3) ts[3] else '0')
        val idx = ((ts.toLongOrNull() ?: 0L).mod(15L)).toInt()
        val (k, iv) = LPX_TABLE[idx]
        val value = aesEnc(umid + LepaoApiCrypto.md5(ts), k.toByteArray(Charsets.UTF_8), iv.toByteArray(Charsets.UTF_8))
        return name to value
    }

    private fun aesEnc(plain: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
    }

    /** rc.c.Z8：nonce % 10 对应的 key / iv 对（与线上环境 kd.o.q=2/3 一致） */
    private val WECHAT_KEY_IV = arrayOf(
        "R7gT3pL9sF2kD5mQ" to "B8jN4wH6xJ2vK7rE",
        "M5dS8fG3hJ7kL2pR" to "C9tY6rU3eW4qX8sZ",
        "P2bN7vF4cD9mK6jA" to "S3nH5gJ8dL2fT9wQ",
        "X6rV9tB3yG5hN7kP" to "E4sW2qA8dZ6fC3jM",
        "K8jD3mF7nH2pL5rT" to "Q6wS4eX9cV2bN7gJ",
        "F3dG7hJ2kL5pM8rN" to "Z9xC4vB6nA2sD7fH",
        "B5vN8cM3xZ6dF2gJ" to "H7kL4jP9mQ2rT5sW",
        "N6mK3lJ7pO2iU5yT" to "G8hF4dS2fA6gH9jK",
        "V2bC7nM3vX6cZ9dF" to "J5kL8jH3gD7fS2dA",
        "H9jG4fD7sA2dF5gH" to "K3lJ6pO9iU2yT5rE"
    )
}