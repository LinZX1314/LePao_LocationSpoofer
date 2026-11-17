package com.kdzw8p.mqa.v71f.xposed.hooks

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers

/** 固定只读端点，复用宿主协议实现，用模块指定档案的会话查询，不切换宿主用户。 */
internal object LepaoRuleReader {
    private const val MODULE = "com.kdzw8p.mqa.v71f"
    private val running = AtomicBoolean(false)
    private var lastRequestId = ""

    fun read(app: Application, loader: ClassLoader, request: Intent) {
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
            XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentProcessName") as? String
        if (process != app.packageName) return
        @Suppress("DEPRECATION")
        val reply = request.getParcelableExtra<PendingIntent>("reply") ?: return
        val moduleUid = runCatching { app.packageManager.getApplicationInfo(MODULE, 0).uid }.getOrNull() ?: return
        if (reply.creatorPackage != MODULE || reply.creatorUid != moduleUid) return
        val id = request.getStringExtra("request_id").orEmpty()
        val expected = request.getStringExtra("uid").orEmpty()
        if (id.isBlank() || (expected.toLongOrNull() ?: 0L) <= 1L) return
        fun send(result: JSONObject) {
            runCatching {
                reply.send(app, 0, Intent().putExtra("result", result
                    .put("request_id", id).put("uid", expected).toString()))
            }
        }
        synchronized(running) {
            // 重复注册的接收器可能收到同一广播；不能让重复回执消费一次性回调。
            if (lastRequestId == id) return
            if (!running.compareAndSet(false, true)) {
                send(JSONObject().put("error", "BUSY"))
                return
            }
            lastRequestId = id
        }
        Thread({
            var stage = "IDENTITY"
            try {
                val session = JSONObject(request.getStringExtra("session") ?: error("SESSION_REQUIRED"))
                val token = session.optString("token").ifBlank { session.optString("access_token") }
                require(session.optString("user_id") == expected && token.isNotBlank())
                stage = "PARAMETERS"
                val path = "https://api2.lptiyu.com/v3/api.php/Run2/beforeRunV260"
                val params = XposedHelpers.callStaticMethod(Class.forName("kd.h", false, loader), "a", path)
                    ?: error("PARAMETERS_UNAVAILABLE")
                // 只替换这次请求的身份，不写 rc.a、SP 或 MMKV，不改变宿主当前账号。
                for (key in listOf("uid", "token", "school_id", "student_num", "auth_num")) {
                    XposedHelpers.callMethod(params, "removeParameter", key)
                }
                for ((key, value) in mapOf("uid" to expected, "token" to token,
                    "school_id" to request.getStringExtra("school_id").orEmpty(),
                    "student_num" to request.getStringExtra("student_no").orEmpty())) {
                    XposedHelpers.callMethod(params, "addBodyParameter", key, value)
                }
                val helper = XposedHelpers.callStaticMethod(Class.forName("kd.m", false, loader), "v")
                    ?: error("HELPER_UNAVAILABLE")
                val base = Class.forName("kd.k", false, loader)
                fun param(name: String): String = XposedHelpers.callStaticMethod(base, "k", params, name) as? String ?: ""
                stage = "SIGNING"
                val model = param("mobileModel")
                val timestamp = param("timestamp")
                require(model.length >= 2 && timestamp.length >= 4)
                val deviceStore = Class.forName("com.lptiyu.tanke.utils.c2", false, loader)
                val umid = (XposedHelpers.callStaticMethod(deviceStore, "i", "umid", "") as? String).orEmpty()
                    .ifBlank { "4x7y0z3a6b9c2d5e8f1g4h7i0j3k6l9m" }
                val md5 = XposedHelpers.callStaticMethod(Class.forName("com.lptiyu.tanke.utils.w0", false, loader), "a", timestamp) as String
                val cipher = Class.forName("com.lptiyu.tanke.utils.a", false, loader)
                val field = "lp" + model.substring(1, 2).lowercase() + timestamp.substring(3, 4)
                val value = XposedHelpers.callStaticMethod(cipher, "g", "2", timestamp.toInt(), umid + md5)
                XposedHelpers.callMethod(params, "addBodyParameter", field, value)
                XposedHelpers.callMethod(helper, "f", params, false)
                val key = param("key")
                require(key.isNotBlank())
                stage = "TRANSPORT"
                val requestType = Class.forName("org.xutils.http.RequestParams", false, loader)
                val wire = requestType.getConstructor(String::class.java).newInstance(path)
                XposedHelpers.callMethod(wire, "addBodyParameter", "key", key)
                XposedHelpers.callMethod(wire, "setConnectTimeout", 10000)
                XposedHelpers.callMethod(wire, "setReadTimeout", 15000)
                val http = XposedHelpers.callStaticMethod(Class.forName("org.xutils.x", false, loader), "http")
                    ?: error("HTTP_UNAVAILABLE")
                val raw = XposedHelpers.callMethod(http, "postSync", wire, String::class.java) as? String
                    ?: error("EMPTY_RESPONSE")
                stage = "RESPONSE"
                val envelope = JSONObject(raw)
                if (envelope.optInt("status", -1) != 1) {
                    send(JSONObject().put("error", envelope.optString("info", "Request failed"))
                        .put("status", envelope.optInt("status", -1)))
                    return@Thread
                }
                stage = "DECRYPTION"
                val plain = if (envelope.optInt("is_encrypt") == 1) {
                    XposedHelpers.callStaticMethod(cipher, "e", "NEW_GAME", envelope.optString("data")) as? String
                } else envelope.opt("data")?.toString()
                val rule = JSONObject(plain ?: error("INVALID_RESPONSE"))
                val owner = rule.optString("uid")
                if ((owner.toLongOrNull() ?: 0L) > 0L && owner != expected) error("RESPONSE_IDENTITY_MISMATCH")
                // uid=0 是实体默认值，归属由本次已签名请求确认，不能用默认值否掉真实业务字段。
                rule.put("uid", expected)
                send(JSONObject().put("rule", redact(rule)).put("fetched_at", System.currentTimeMillis())
                    .put("rule_context", JSONObject().put("umid", umid)
                        .put("mobileDeviceId", param("mobileDeviceId")).put("mobileModel", model)
                        .put("mobileOsVersion", param("mobileOsVersion"))
                        .put("version", param("version")).put("version_name", param("version_name"))))
            } catch (_: Throwable) {
                // 异常文本可能包含签名或请求参数，不跨进程传递原始异常。
                send(JSONObject().put("error", "HOST_QUERY_FAILED").put("stage", stage))
            } finally {
                running.set(false)
            }
        }, "lepao-rule-reader").start()
    }

    private fun redact(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key ->
                put(key, if (Regex("(?i)token|secret|password|cookie|signature").containsMatchIn(key))
                    "[redacted]" else redact(value.opt(key)))
            }
        }
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(redact(value.opt(i))) }
        is String -> if (value.startsWith("https://") || value.startsWith("http://")) value.substringBefore('?').substringBefore('#') else value
        else -> value
    }
}
