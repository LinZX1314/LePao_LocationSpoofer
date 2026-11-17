package com.kdzw8p.mqa.v71f.utils

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 乐跑 v3 接口的签名与响应解密。
 *
 * 签名（kd.k.f）：把所有 body/query 参数按 key 排序，拼 key+value，末尾追加 `rc.b.d(uri)`：
 * - uri 不在 rc.c.w7 时追加 rc.b.a = "rDJiNB9j7vD2"；
 * - 在 w7（登录 / beforeRun / stopRun / 位置上报等）时改为前置 rc.b.b = "eFoqdHOdDy6ViJ0i"，
 *   且 mobileDeviceId 不参与签名。
 * 最后取 MD5 小写作为 sign。
 *
 * 响应解密（a.e("NEW_GAME", data)）：AES/CBC/PKCS5Padding，
 * key = "Wet2C8d34f62ndi3"，iv = "K6iv85jBD8jgf32D"，Base64(DEFAULT)。
 */
object LepaoApiCrypto {

    private const val SIGN_SECRET = "rDJiNB9j7vD2"
    private const val SIGN_SECRET_NEW = "eFoqdHOdDy6ViJ0i"
    private const val RESPONSE_KEY = "Wet2C8d34f62ndi3"
    private const val RESPONSE_IV = "K6iv85jBD8jgf32D"

    /** 普通接口（学校列表、历史记录等）的签名 */
    fun sign(params: Map<String, String>): String =
        md5(params.entries.sortedBy { it.key }.joinToString("") { it.key + it.value } + SIGN_SECRET)

    /** w7 列表接口的签名：secret 在前，mobileDeviceId 不参与 */
    fun signNew(params: Map<String, String>): String =
        SIGN_SECRET_NEW + params.entries
            .filter { it.key != "mobileDeviceId" }
            .sortedBy { it.key }
            .joinToString("") { it.key + it.value }

    /** 把签名结果补进参数表后返回新表（不修改入参） */
    fun signed(params: Map<String, String>): Map<String, String> =
        LinkedHashMap(params).apply { put("sign", sign(params)) }

    /** uri 是否命中 w7（新版签名 + 加密 key 参数） */
    fun isNewSignEndpoint(uri: String): Boolean = NEW_SIGN_ENDPOINTS.any { uri.contains(it) }

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 255
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * 响应里的 data 可能是对象，也可能是加密字符串；统一转成 JSONObject。
     * 解密失败返回 null，由调用方给出“响应解析失败”而不是伪造数据。
     */
    fun dataObject(root: JSONObject): JSONObject? {
        val data = root.opt("data")
        return when (data) {
            is JSONObject -> data
            is String -> {
                val plain = if (data.trimStart().startsWith("{")) data else decrypt(data) ?: return null
                try {
                    JSONObject(plain)
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    /** beforeRun 请求密钥与 NEW_GAME 响应密钥不同，沿用 x-run-cli 的请求包装。 */
    fun encryptRunRequest(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,
            SecretKeySpec("goMEzJ1pDpncNHb9".toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec("ZUrAS2lUidjyY2gK".toByteArray(Charsets.UTF_8)))
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 请求包装方向：明文 JSON → AES 加密 → Base64（登录接口 body 的 key= 值） */
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(RESPONSE_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(RESPONSE_IV.toByteArray(Charsets.UTF_8))
        )
        Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
    } catch (_: Exception) {
        ""
    }

    fun decrypt(data: String): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(RESPONSE_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(RESPONSE_IV.toByteArray(Charsets.UTF_8))
        )
        String(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    // rc.c.w7：新版签名接口（mobileDeviceId 不参与签名）
    private val NEW_SIGN_ENDPOINTS = arrayOf(
        "Login/quickLoginV339",
        "Login/RefreshToken",
        "Run2/beforeRunV260",
        "Run/stopRunV278",
        "Run2/gyroscope",
        "Run/setRunLocationRecord",
        "Report/getOssSign",
        "report/getOssSts"
    )
}