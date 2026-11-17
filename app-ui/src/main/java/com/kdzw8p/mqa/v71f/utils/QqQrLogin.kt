package com.kdzw8p.mqa.v71f.utils

import android.net.Uri
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 移动应用必须使用 openmobile 扫码入口；PC authorize 不识别乐跑的移动 AppID。 */
internal object QqQrLogin {
    const val APP_ID = "1105660458"
    private const val REDIRECT = "auth://tauth.qq.com/"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    class Session {
        private val state = UUID.randomUUID().toString()
        val url: String = Uri.parse("https://openmobile.qq.com/oauth2.0/m_authorize").buildUpon()
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("client_id", APP_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", "get_user_info")
            .appendQueryParameter("state", state)
            .appendQueryParameter("display", "mobile")
            .appendQueryParameter("style", "qr")
            .build().toString()

        fun isCallback(url: String): Boolean {
            val uri = Uri.parse(url)
            return uri.scheme == "auth" && uri.host == "tauth.qq.com" &&
                uri.path.orEmpty() in listOf("", "/") && uri.port == -1 && uri.userInfo == null
        }

        /** 只接受当前会话的回调，错误信息使用固定文本，避免把含 token 的 URL 带入日志。 */
        fun accessToken(url: String): String? {
            if (!isCallback(url)) return null
            val uri = Uri.parse(url)
            val params = Uri.parse("https://callback.invalid/?" + uri.encodedFragment.orEmpty())
            if (params.getQueryParameter("state") != state) return null
            if (!params.getQueryParameter("error").isNullOrBlank()) return null
            return params.getQueryParameter("access_token")?.takeIf { it.isNotBlank() }
        }
    }

    /** openid 必须由这份 token 对应的 QQ 应用返回，不接受其他应用的身份串用。 */
    suspend fun resolve(token: String): LepaoDirectLogin.QqTokens? = withContext(Dispatchers.IO) {
        try {
            val url = Uri.parse("https://graph.qq.com/oauth2.0/me").buildUpon()
                .appendQueryParameter("access_token", token)
                .appendQueryParameter("fmt", "json")
                .build().toString()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start < 0 || end < start) return@withContext null
                val json = JSONObject(body.substring(start, end + 1))
                val openid = json.optString("openid")
                if (json.optString("client_id") != APP_ID || openid.isBlank()) null
                else LepaoDirectLogin.QqTokens(openid, token)
            }
        } catch (_: Exception) {
            null
        }
    }
}
