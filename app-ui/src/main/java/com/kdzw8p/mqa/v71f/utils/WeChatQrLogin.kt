package com.kdzw8p.mqa.v71f.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.tencent.mm.opensdk.diffdev.DiffDevOAuthFactory
import com.tencent.mm.opensdk.diffdev.IDiffDevOAuth
import com.tencent.mm.opensdk.diffdev.OAuthErrCode
import com.tencent.mm.opensdk.diffdev.OAuthListener
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 微信「移动应用扫码登录」：用乐跑自己的开放平台 appid + AppSecret 取 sdk_ticket，
 * 生成签名后由微信 SDK 拉取二维码。手机微信扫码授权后，SDK 直接回调 authCode，
 * 不依赖 redirect_uri，也不需要本机安装微信。二维码页面会显示「步道乐跑」。
 */
object WeChatQrLogin {

    const val APP_ID = "wx5a2e1ff396785475"
    private const val APP_SECRET = "cdb20f1be2d3cac7f46664c742937a54"
    private const val SCOPE = "snsapi_userinfo"

    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var oauth: IDiffDevOAuth? = null

    interface Callback {
        fun onQr(bitmap: Bitmap)
        fun onScanned()
        fun onCode(code: String)
        fun onError(message: String)
    }

    fun start(callback: Callback) {
        stop()
        Thread {
            try {
                val tokenJson = getJson(
                    "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential" +
                        "&appid=$APP_ID&secret=$APP_SECRET"
                )
                val accessToken = tokenJson.optString("access_token")
                if (accessToken.isBlank()) {
                    fail(callback, "获取 access_token 失败：${tokenJson.optString("errmsg")}")
                    return@Thread
                }
                val ticketJson = getJson(
                    "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=$accessToken&type=2"
                )
                val sdkTicket = ticketJson.optString("ticket")
                if (sdkTicket.isBlank()) {
                    fail(callback, "获取 sdk_ticket 失败：${ticketJson.optString("errmsg")}")
                    return@Thread
                }

                val nonce = (1..16).map { "abcdef0123456789".random() }.joinToString("")
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val raw = "appid=$APP_ID&noncestr=$nonce&sdk_ticket=$sdkTicket&timestamp=$timestamp"
                val signature = MessageDigest.getInstance("SHA-1")
                    .digest(raw.toByteArray())
                    .joinToString("") { "%02x".format(it) }

                val api = DiffDevOAuthFactory.getDiffDevOAuth()
                oauth = api
                val listener = object : OAuthListener {
                    override fun onAuthGotQrcode(qrcodeImgPath: String?, imgBuf: ByteArray?) {
                        val bitmap = imgBuf?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        main.post {
                            if (bitmap != null) callback.onQr(bitmap)
                            else callback.onError("二维码解析失败")
                        }
                    }

                    override fun onQrcodeScanned() {
                        main.post { callback.onScanned() }
                    }

                    override fun onAuthFinish(errCode: OAuthErrCode?, authCode: String?) {
                        main.post {
                            if (errCode == OAuthErrCode.WechatAuth_Err_OK && !authCode.isNullOrBlank()) {
                                callback.onCode(authCode)
                            } else {
                                callback.onError("授权失败：${errCode?.name ?: "unknown"}")
                            }
                        }
                    }
                }
                val started = api.auth(APP_ID, SCOPE, nonce, timestamp, signature, listener)
                if (!started) fail(callback, "微信 SDK 启动授权失败")
            } catch (t: Throwable) {
                fail(callback, t.message ?: t.javaClass.simpleName)
            }
        }.start()
    }

    fun stop() {
        runCatching { oauth?.stopAuth() }
        oauth = null
    }

    private fun fail(callback: Callback, message: String) {
        main.post { callback.onError(message) }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            JSONObject(response.body?.string().orEmpty())
        }
    }
}