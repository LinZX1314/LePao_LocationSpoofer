@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.kdzw8p.mqa.v71f.xposed.hooks

import com.kdzw8p.mqa.v71f.xposed.LocationHooker
import com.kdzw8p.mqa.v71f.xposed.utils.XposedBridge
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标应用检测层接管（按检测项逐条开关）。
 *
 * 与 AntiDetectionHooker 的分工：那边拦的是「系统级模拟痕迹」（AppOps、Settings.Secure、
 * 权限自检），所有进程通用；这里拦的是「目标应用自己写的检测函数」——它们是纯 Java、
 * 有明确的类名和方法名，所以可以做到一项一个开关，同时把命中情况写进日志。
 *
 * 每个检测项都独立成 key（`detect_*`），落盘位置与「环境 hook 分组」共用同一个
 * `target_hook_groups` 配置对象，因此 UI 端的读写路径完全复用，Xposed 端在
 * handleLoadPackage 时装一次，改动后重启目标进程生效。
 *
 * 覆盖的两套检测代码（均来自对 com.lptiyu.tanke 的反编译结果）：
 * - 乐跑自研：com.lptiyu.tanke.utils.q0（IsEmulator：qemu 管道 / tty drivers / 模拟器号码）、
 *   com.lptiyu.tanke.utils.i（AvdUtils：maps 里的 Substrate / XposedBridge.jar、堆栈特征）、
 *   com.lptiyu.tanke.utils.l2.s（遍历已安装应用的 ACCESS_MOCK_LOCATION）。
 * - 快手广告 SDK：com.kwad.sdk.utils.s 的 a/d/e 三组子检查（Build 特征、root 二进制、
 *   Xposed 包名与 /proc/self/maps），以及汇总位 com.kwad.sdk.k.a.a.cF。
 * - RootBeer：jg.c 的全部 boolean 探针。
 *
 * q0.a(Context) 与构造函数由 VMP（JniLib.cV/cZ）转发到 native，Java 层的
 * declaredMethods 仍然包含它们，所以在这一层 hook 能同时覆盖 Java 直调和 VMP 转发两条路径。
 */

/**
 * 服务端作弊应用黑名单（检测层第一层）：BeforeRunFragment.Y2 读 this.Z.android_cheats，
 * 命中就弹「手机系统环境异常」并删应用目录。在 Y2 执行前把 android_cheats 置空即可，
 * 时段校验（I3）等其它逻辑不受影响。
 */
private fun hookCheatBlacklist(classLoader: ClassLoader): Int {
    val c = XposedHelpers.findClassIfExists(
        "com.lptiyu.tanke.fragments.schoolrundetail.BeforeRunFragment", classLoader
    ) ?: return 0
    var hooked = 0
    for (m in c.declaredMethods) {
        if (m.name != "Y2" || m.parameterCount != 0) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val frag = chain.thisObject
                        if (frag != null) {
                            val zField = frag.javaClass.getDeclaredField("Z").apply { isAccessible = true }
                            val resp = zField.get(frag)
                            if (resp != null) {
                                val cheats = resp.javaClass.getField("android_cheats").apply { isAccessible = true }
                                cheats.set(resp, java.util.ArrayList<String>())
                            }
                        }
                    } catch (_: Throwable) {
                        // 字段名随版本变化时跳过，不阻断 Y2
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
        } catch (_: Throwable) {
            // 挂不上不影响其它项
        }
    }
    return hooked
}

/** maps 扫描黑名单 token：Java 层读 /proc/<pid>/maps 时把含这些 token 的行整条抹掉 */
private val MAPS_FILTER_TOKENS = listOf(
    "dexposed", "frida", "saurik.substrate", "xposedbridge", "lsposed", "org.lsposed",
    "adbi_hook", "riru", "zygisk", "magisk", "edxp", "meowcat", "substrate"
)

/**
 * Java 层的 /proc/maps 过滤：把 FileInputStream / FileReader 打开的 maps 路径换成一份
 * 过滤后的临时副本。app 进程没有 root，临时文件落在自己的 tmpdir（/data/user/0/<pkg>）。
 * 覆盖 com.lptiyu.tanke.utils.i 与 kwad s$e 的 Java maps 扫描；壳层 native 的 fopen
 * 不经过这里，但其黑名单没有 LSPosed 特征，LSPosed 的 ART 内联 hook 不注入独立 .so，
 * 实测不命中。
 */
private val mapsFilterLock = Any()
private var lastMapsPath: String? = null
private var lastMapsCache: String? = null

private fun hookMapsFilter(): Int {
    var hooked = 0
    for (clazz in arrayOf(java.io.FileInputStream::class.java, java.io.FileReader::class.java)) {
        for (ctor in clazz.constructors) {
            if (ctor.parameterCount != 1 || ctor.parameterTypes[0] != String::class.java) continue
            try {
                XposedHelpers.module.hook(ctor).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val p = chain.args.getOrNull(0) as? String
                        if (p != null && p.startsWith("/proc/") && p.endsWith("/maps")) {
                            val filtered = readMapsFiltered(p)
                            if (filtered != null) {
                                val args = chain.args.toTypedArray()
                                args[0] = filtered
                                return chain.proceed(args)
                            }
                        }
                        return chain.proceed(chain.args.toTypedArray())
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }
    return hooked
}

private fun readMapsFiltered(path: String): String? {
    synchronized(mapsFilterLock) {
        if (lastMapsPath == path && lastMapsCache != null) return lastMapsCache
    }
    val filtered = try {
        val sb = StringBuilder()
        java.io.RandomAccessFile(path, "r").use { ra ->
            var line: String? = ra.readLine()
            while (line != null) {
                val lower = line.lowercase()
                if (!MAPS_FILTER_TOKENS.any { lower.contains(it) }) sb.append(line).append('\n')
                line = ra.readLine()
            }
        }
        sb.toString()
    } catch (_: Throwable) {
        return null
    }
    val tmp = try {
        java.io.File.createTempFile("ls_maps_", ".txt")
    } catch (_: Throwable) {
        return null
    }
    try {
        tmp.writeText(filtered)
    } catch (_: Throwable) {
        return null
    }
    synchronized(mapsFilterLock) {
        lastMapsPath = path
        lastMapsCache = tmp.absolutePath
    }
    return tmp.absolutePath
}


internal fun LocationHooker.hookLepaoDetection(
    classLoader: ClassLoader,
    enabled: (String) -> Boolean
) {
    val installed = LinkedHashMap<String, Int>()
    LepaoRecordSchemaCompatibility.install(classLoader)
    try {
        hookLepaoDetectionImpl(classLoader, enabled, installed)
    } catch (t: Throwable) {
        // 绝不让异常静默：完整堆栈写日志，否则无法定位是哪一个 hook 中断了整链
        XposedBridge.log(
            "[R7VqzN] hookLepaoDetection CRASHED: " +
                "${t.javaClass.name}: ${t.message}\n" +
                t.stackTrace.joinToString("\n") { "  at $it" }
        )
    }
    XposedBridge.log(
        "[R7VqzN] lepao detection hooks: " +
            installed.entries.joinToString(", ") { "${it.key}=${it.value}" }
    )
}

/** 与 core-data LepaoBridge.VERSION 保持一致；界面用它判断 LSPosed 是否真的重载了模块 */
private const val WX_BRIDGE_VERSION = "v8"
private const val WX_RESULT_ACTION = "com.kdzw8p.mqa.v71f.LEPAO_WX_RESULT"
private const val MODULE_PKG = "com.kdzw8p.mqa.v71f"
private const val BRIDGE_DONE = 1
private const val BRIDGE_WAIT = 0

private fun LocationHooker.currentApp(): android.app.Application? = lepaoApp()

private fun lepaoApp(): android.app.Application? = runCatching {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? android.app.Application
}.getOrNull()

/** 回执：广播给模块 App（界面逐步显示）+ 落一份 /sdcard/Download/lepao_bridge.log（adb 可看） */
private fun LocationHooker.sendWxResult(status: String, message: String, requestId: String) =
    broadcastWxResult(status, message, requestId)

private fun broadcastWxResult(status: String, message: String, requestId: String) {
    XposedBridge.log("[R7VqzN] wx bridge $WX_BRIDGE_VERSION [$status] $message")
    runCatching {
        lepaoApp()?.sendBroadcast(
            android.content.Intent(WX_RESULT_ACTION)
                .setPackage(MODULE_PKG)
                .putExtra("status", status)
                .putExtra("message", message)
                .putExtra("request_id", requestId)
                .putExtra("version", WX_BRIDGE_VERSION)
        )
    }
    runCatching {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        java.io.File(dir, "lepao_bridge.log").appendText("$stamp [$status] $message\n")
    }
}

private fun LocationHooker.bridgeWxLogin(classLoader: ClassLoader) {
    // p2.f 内部的 xUtils 会建 Handler，必须在主线程调用，否则报 Looper.prepare()
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var attempts = 0
    val runnable = object : Runnable {
        override fun run() {
            attempts++
            val code = try {
                runWxBridgeOnce(classLoader)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                sendWxResult("error", "调用乐跑登录失败 ${cause.javaClass.simpleName}: ${cause.message}", "")
                XposedBridge.log("[R7VqzN] wx bridge stack:\n" + android.util.Log.getStackTraceString(cause))
                return
            }
            if (code == BRIDGE_DONE) return
            if (attempts >= 10) {
                sendWxResult("error", "20 秒内没读到下发配置 lepao_wx_login，请在界面重新点导入", "")
                return
            }
            handler.postDelayed(this, 2000)
        }
    }
    handler.post(runnable)
}

/** 返回 BRIDGE_DONE 表示不用再重试（回执已发）；BRIDGE_WAIT 表示配置还没到位 */
private fun LocationHooker.runWxBridgeOnce(classLoader: ClassLoader): Int {
    val app = currentApp() ?: return BRIDGE_WAIT
    val cfg = readConfig()?.optJSONObject("lepao_wx_login") ?: return BRIDGE_WAIT
    val openid = cfg.optString("openid")
    val token = cfg.optString("access_token")
    val loginType = cfg.optInt("type", 3)
    if (loginType != 2 && loginType != 3) return BRIDGE_DONE
    val platformLabel = if (loginType == 2) "QQ" else "微信"
    val requestId = cfg.optString("request_id")
    if (openid.isBlank() || token.isBlank()) {
        sendWxResult("error", "下发的凭据不完整（openid / access_token 为空）", requestId)
        return BRIDGE_DONE
    }
    val marker = java.io.File(app.filesDir, "ls_wx_bridge_id")
    if (requestId.isNotBlank() && marker.exists() && marker.readText() == requestId) {
        // 同一份凭据已由一次调用发起，最终结论由那次的 15 秒计时器给出；这里不能回 ok，否则界面会把旧结果当成本次成功
        sendWxResult("pending", "同一份凭据已在乐跑进程中处理，等待登录结果…", requestId)
        return BRIDGE_DONE
    }
    sendWxResult("pending", "乐跑已收到凭据，正在用 App 自己的 p2.f 走 quickLoginV339", requestId)

    val rcA = XposedHelpers.findClassIfExists("rc.a", classLoader)
    if (rcA == null) {
        sendWxResult("error", "找不到 rc.a（乐跑版本不匹配）", requestId)
        return BRIDGE_DONE
    }
    XposedHelpers.callStaticMethod(rcA, "O1", openid)
    XposedHelpers.callStaticMethod(rcA, "Q1", token)

    val p2Cls = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.p2", classLoader)
    if (p2Cls == null) {
        sendWxResult("error", "找不到 com.lptiyu.tanke.utils.p2（乐跑版本不匹配）", requestId)
        return BRIDGE_DONE
    }
    val helper = XposedHelpers.newInstance(p2Cls)
    // p2.f 只负责发请求：成功回包经实例监听器 h（n(g) 注入）分发，原 App 的监听器在 native 里
    // 调 rc.a.V0(UserDataEntity) 写登录态。裸 newInstance 的 h=null 会让回包无人处理
    // （此前「回调正常但登录态不落盘」的根因），这里补一个代理监听器复现原写入链。
    val gCls = Class.forName("com.lptiyu.tanke.utils.p2\$g", false, classLoader)
    val settled = java.util.concurrent.atomic.AtomicBoolean(false)
    val gListener = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(gCls)) { proxy, m, args ->
        when (m.name) {
            "c" -> {
                settled.set(true)
                val result = args?.get(0)
                val status = runCatching { result?.javaClass?.getField("status")?.getInt(result) }.getOrDefault(-1)
                val info = runCatching { result?.javaClass?.getField("info")?.get(result) as? String }.getOrNull()
                val data = runCatching { result?.javaClass?.getField("data")?.get(result) }.getOrNull()
                if (status == 1 && data != null) {
                    val uid = runCatching { data.javaClass.getField("uid").getLong(data) }.getOrDefault(0L)
                    val written = runCatching {
                        require(uid > 1L)
                        XposedHelpers.callStaticMethod(rcA, "V0", data)
                        val actual = (XposedHelpers.callStaticMethod(rcA, "x") as Number).toLong()
                        require(actual == uid)
                        val mmkv = lepaoMmkvInstance ?: mmkvSettingInstance(classLoader) ?: error("STORE_UNAVAILABLE")
                        exportLepaoUser(mmkv, requestId)
                    }.isSuccess
                    if (written) sendWxResult("ok", "乐跑服务端登录成功，已按原链路写入登录态（uid=$uid）", requestId)
                    else sendWxResult("error", "服务端登录已返回，但宿主写回校验失败", requestId)
                } else {
                    sendWxResult("error", "乐跑服务端拒绝这次${platformLabel}登录：status=$status info=$info", requestId)
                }
                null
            }
            "b" -> { settled.set(true); sendWxResult("error", "乐跑登录流程回报失败：${args?.get(0)}", requestId); null }
            "onError" -> { settled.set(true); sendWxResult("error", "乐跑${platformLabel}授权 onError：${args?.getOrNull(2)}", requestId); null }
            "onCancel" -> { settled.set(true); sendWxResult("error", "乐跑${platformLabel}授权 onCancel，登录未完成", requestId); null }
            "toString" -> "lepao-wx-g-proxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> false
            else -> null
        }
    }
    runCatching { p2Cls.getMethod("n", gCls).invoke(helper, gListener) }.onFailure {
        sendWxResult("error", "注入 p2 监听器失败，登录回包将无人处理：${it.message}", requestId)
        return BRIDGE_DONE
    }
    val method = p2Cls.getMethod(
        "f", String::class.java, Int::class.javaPrimitiveType, String::class.java,
        String::class.java, String::class.java, String::class.java, String::class.java
    )
    method.invoke(
        helper, openid, loginType, cfg.optString("avatar"), cfg.optString("nick"),
        "", cfg.optString("school_id"), ""
    )
    runCatching { marker.writeText(requestId) }
    sendWxResult("pending", "已调用乐跑${platformLabel}登录，等服务端返回…", requestId)
    // p2.f 是异步网络请求：15 秒后看乐跑有没有回写登录态，给一个确定的成功/失败结论
    val invokeAt = System.currentTimeMillis()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        // 回调已经给出确定结论时不再重复播报；这里只兜底「回包始终没到」的情况
        if (settled.get()) return@postDelayed
        val json = runCatching {
            org.json.JSONObject(java.io.File(app.filesDir, "ls_token_capture.json").readText())
        }.getOrNull()
        if (json != null && json.optLong("captured_at", 0L) >= invokeAt - 2000) {
            broadcastWxResult("ok", "乐跑登录成功，已写入登录态（uid=${json.optString("uid")}）", requestId)
        } else {
            broadcastWxResult(
                "error",
                "15 秒内乐跑没有回写登录态（ls_token_capture.json 未更新）；具体原因未知，请查看乐跑日志与模块 wx_log",
                requestId
            )
        }
    }, 15000)
    return BRIDGE_DONE
}

/** 切换写回：组装 UserDataEntity 走乐跑自己的 rc.a.V0（SP+MMKV+内存三处一起写）；返回空串=成功，否则为失败点 */
private fun applyLepaoSwitch(classLoader: ClassLoader, sw: org.json.JSONObject): String {
    val rcA = XposedHelpers.findClassIfExists("rc.a", classLoader)
        ?: return "找不到 rc.a"
    val entityCls = XposedHelpers.findClassIfExists("com.lptiyu.tanke.entity.response.UserDataEntity", classLoader)
        ?: return "找不到 UserDataEntity"
    val entity = runCatching { entityCls.newInstance() }.getOrElse {
        return "实体构造失败 ${it.javaClass.simpleName}: ${it.message}"
    }
    fun put(field: String, value: Any?) {
        if (value == null) return
        runCatching { entityCls.getField(field).set(entity, value) }
            .onFailure { XposedBridge.log("[R7VqzN] switch put $field failed: ${it.message}") }
    }
    put("uid", sw.optString("user_id").toLongOrNull() ?: 0L)
    put("role", sw.optInt("role", 1))
    put("is_admin", sw.optInt("is_admin", 0))
    put("phone", sw.optString("phone"))
    put("name", sw.optString("name"))
    put("img", sw.optString("img"))
    put("access_token", sw.optString("access_token"))
    put("refresh_token", sw.optString("refresh_token"))
    put("refresh_expire", sw.optString("refresh_expire"))
    return runCatching {
        XposedHelpers.callStaticMethod(rcA, "V0", entity)
        ""
    }.exceptionOrNull()?.let { "V0 调用失败 ${it.javaClass.simpleName}: ${it.message}" } ?: ""
}

private fun hookSplashDeepLink(classLoader: ClassLoader): Int {
    val cls = XposedHelpers.findClassIfExists("com.lptiyu.tanke.activities.splash.SplashActivity", classLoader) ?: return 0
    return try {
        XposedHelpers.hookAllMethods(cls, "onCreate") { chain, _ ->
            try {
                val activity = chain.thisObject as? android.app.Activity
                val data = activity?.intent?.dataString
                if (!data.isNullOrBlank()) {
                    val uri = android.net.Uri.parse(data)
                    val keys = uri.queryParameterNames?.joinToString(",").orEmpty()
                    XposedBridge.log("[R7VqzN] deeplink ${uri.scheme}://${uri.host} params=$keys")
                    runCatching {
                        java.io.File("/data/data/com.lptiyu.tanke/files/ls_deeplink.txt").writeText(data)
                    }
                }
            } catch (_: Throwable) {
            }
            chain.proceed(chain.args.toTypedArray())
        }
        1
    } catch (_: Throwable) {
        0
    }
}

private fun LocationHooker.hookLepaoDetectionImpl(
    classLoader: ClassLoader,
    enabled: (String) -> Boolean,
    installed: LinkedHashMap<String, Int>
) {
    // 回执通道最先注册：注册本身极轻（函数内每秒重试等 Application），不能排在几十秒的
    // hook 安装序列后面，否则冷启动时切换回执必超时（2026-10-24 15:51/15:59 实测）
    registerUserExportReceiver(classLoader)

    // 开屏广告跳过必须最早注册：广告在 Application onCreate 前就可能出现（SplashActivity
    // 首帧），deferUntilAppReady 会晚于首帧导致错过点击窗口（2026-10-25 实测）
    if (enabled("block_ad_screens")) {
        registerSplashAdSkipper(classLoader)
        installed["splash_ad_skip"] = 1
    }

    // 0) 原生 shell spoof（隐藏开关，默认开）：把模块自带的 libls_spoofer_native.so 里
    // 的 fopen / __system_property_get hook 挂上，补 Java 层拦不住的 C 级读取。
    // .so 尚未在 NDK 下编译过时 loadLibrary 失败，只记日志不阻断其余 hook
    if (enabled("native_shell_spoof")) {
        installed["native_shell_spoof"] = loadNativeShellSpoof(classLoader)
    }

    // 1) 乐跑自研模拟器检测：q0 的 a/b/c/d 全部是 boolean 或 Boolean 返回值。
    // q0 由加壳运行期注入，early 阶段访问 declaredMethods 会触发壳类初始化并死锁，放延迟组。
    if (enabled("detect_q0_emulator")) {
        deferUntilAppReady(classLoader) {
            val c = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.q0", classLoader)
            if (c != null) installed["detect_q0_emulator"] = hookBooleanMethods(c) { true }
        }
    }

    // 2) qemu 设备节点：不管调用方是 Java 还是 VMP 转发的 native，最终都落在 File.exists 上。
    // 注意：File.exists 是高频核心方法，启动早期安装会在类加载路径上反复回调导致卡死；
    // 必须放到延迟组（应用就绪后）再装。
    if (enabled("detect_q0_paths")) {
        deferUntilAppReady(classLoader) {
            installed["detect_q0_paths"] = hookQemuDevicePaths()
        }
    }

    // 3) 乐跑自研 AvdUtils：maps 扫描 + 堆栈特征
    if (enabled("detect_avd_maps")) {
        val c = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.i", classLoader)
        if (c != null) installed["detect_avd_maps"] = hookBooleanMethods(c) { true }
    }

    // 4) 本地已安装应用里找声明 ACCESS_MOCK_LOCATION 的包（l2.s 两个参数、boolean 返回值）
    if (enabled("detect_mock_app_scan")) {
        val c = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.l2", classLoader)
        if (c != null) installed["detect_mock_app_scan"] = hookBooleanMethods(c) { m ->
            m.name == "s" && m.parameterCount == 2
        }
    }

    // 5) 快手 SDK：Build 特征 + ro.kernel.qemu
    if (enabled("detect_kwad_emulator")) {
        deferUntilAppReady(classLoader) {
            installed["detect_kwad_emulator"] = hookKwadSubChecks(classLoader, "com.kwad.sdk.utils.s\$a")
        }
    }

    // 6) 快手 SDK：/system/app/Superuser.apk、su 二进制、which、/data/su_test
    if (enabled("detect_kwad_root")) {
        deferUntilAppReady(classLoader) {
            installed["detect_kwad_root"] = hookKwadSubChecks(classLoader, "com.kwad.sdk.utils.s\$d")
        }
    }

    // 7) 快手 SDK：Xposed 安装器包名、堆栈、/proc/self/maps
    if (enabled("detect_kwad_xposed")) {
        deferUntilAppReady(classLoader) {
            installed["detect_kwad_xposed"] = hookKwadSubChecks(classLoader, "com.kwad.sdk.utils.s\$e")
        }
    }

    // 8) 快手 SDK 汇总位：cF 是所有子检查的唯一出口，返回 false 即整份上报清零
    if (enabled("detect_kwad_aggregate")) {
        val c = XposedHelpers.findClassIfExists("com.kwad.sdk.k.a.a", classLoader)
        if (c != null) {
            installed["detect_kwad_aggregate"] = hookBooleanMethods(c) { m -> m.name == "cF" }
        }
    }

    // 9) RootBeer：jg.c 的 24 个探针（su 路径 / test-keys / busybox / mount rw / magisk ...）
    if (enabled("detect_rootbeer")) {
        val c = XposedHelpers.findClassIfExists("jg.c", classLoader)
        if (c != null) installed["detect_rootbeer"] = hookBooleanMethods(c) { true }
    }

    // 9b) RootBeer native 入口：VMP 若绕过 jg.c 直接调 native，这里兜底。
    // checkForRoot 固定返回 0（未命中），setLogDebugMessages 返回 true（只影响日志）
    if (enabled("detect_rootbeer_native")) {
        val nativeBeer = XposedHelpers.findClassIfExists(
            "com.scottyab.rootbeer.RootBeerNative", classLoader
        )
        if (nativeBeer != null) {
            var n = XposedHelpers.hookAllMethods(nativeBeer, "checkForRoot") { _, _ -> 0 }.size
            n += XposedHelpers.hookAllMethods(nativeBeer, "setLogDebugMessages") { _, _ -> true }.size
            installed["detect_rootbeer_native"] = n
        }
    }

    // 9c) 快手 JvmtiHelper：SDK 会主动开关 JDWP，调试端口状态不应被业务读到
    if (enabled("detect_jvmti")) {
        val jvmti = XposedHelpers.findClassIfExists(
            "com.kwad.sdk.crash.utils.JvmtiHelper", classLoader
        )
        if (jvmti != null) {
            var n = 0
            for (name in listOf("enableJdwp", "disableJdwp", "avoidDeoptForReleaseBuild", "unloadAgent")) {
                n += XposedHelpers.hookAllMethods(jvmti, name) { _, _ -> null }.size
            }
            installed["detect_jvmti"] = n
        }
    }

    // 10) 系统属性：基础键（qemu/secure/debuggable/tags）真机与模拟器都生效；
    // 设备身份键仅在 looksLikeEmulator 为真时改写。looksLikeEmulator 必须在第 11b 步
    // （spoofBuildFields 写入 Build 字段）之前计算，读到的才是原始值
    val deviceSpoofActive = looksLikeEmulator()
    if (enabled("detect_system_properties") || enabled("detect_device_spoof")) {
        installed["detect_system_properties"] = hookSystemProperties(deviceSpoofActive)
    }

    // 11) 服务端作弊应用黑名单：BeforeRunFragment.Y2 跑之前把 android_cheats 清成空，
    // 保留时段校验（I3），只拆除 android_cheats 命中分支
    if (enabled("detect_cheat_blacklist")) {
        installed["detect_cheat_blacklist"] = hookCheatBlacklist(classLoader)
    }

    // 11b) 设备身份伪装（Build 字段）：模拟器才改写；普通手机上真机指纹本来就是干净的，
    // 写假的三星身份反而与 native 属性不一致，变成新的可检测点
    if (enabled("detect_device_spoof")) {
        installed["detect_device_spoof"] = spoofBuildFields(deviceSpoofActive)
    }

    // 11c) 模拟器表面文件/传感器/开发者选项：x86 MuMu 上 native 库不加载，
    // Build 伪装也盖不到 cpuinfo、网卡 MAC、传感器厂商，这几处直接暴露 ranchu/goldfish。
    if (enabled("detect_device_spoof")) {
        installed["detect_emulator_surface"] = hookEmulatorSurface()
    }

    // 11d) 4.3.3 壳的 native 解密库加载拦截已提前到 handleLoadPackage（壳在
    // attachBaseContext 就加载，这里太晚），见 installKadpBlockEarly。

    // 12) /proc/<pid>/maps 内容过滤：Java 层读 maps 的扫描器（i.java、kwad s$e）
    // 拿到的行里不再含 dexposed / frida / XposedBridge 等 token。壳层 native 的
    // libDexHelper::isHooked 走 C 的 fopen，Java hook 拦不住，但它的黑名单本就没有
    // LSPosed，只查独立注入的 .so 特征；LSPosed 用 ART 内联 hook，不会命中。
    // 12) /proc/<pid>/maps 内容过滤。
    // 注意：File / RandomAccessFile 的 hook 会落到 dex 加载路径上，早期安装可能导致
    // 乐跑壳的 InitializationProvider 加载失败（ClassNotFoundException）。
    // 放延迟组，等 Provider 初始化完成后再装。
    if (enabled("detect_maps_filter")) {
        deferUntilAppReady(classLoader) {
            installed["detect_maps_filter"] = hookMapsFilter()
        }
    }

    // 13) 广告屏拦截：startActivity 目标为广告 SDK 的界面（kwad/字节 Pangle/益方/倍孜）时直接取消，
    // 广告插屏/激励视频/开屏页不再弹出；只拦界面拉起，底层广告请求与业务回调原样保留
    // 注意：splash_ad_skip 已提前到 hook 序列最开头注册，这里只保留 block_ad_screens 的其他逻辑
    if (enabled("block_ad_screens")) {
        installed["block_ad_screens"] = hookAdScreens(classLoader)
    }

    // 14) 内置广告位关闭：乐跑自带 6 个 close*Ad 开关（RunApplication 公有字段），
    // 默认 false、由登录/奖励流程置位。getInstance 返回实例时把 6 个 close*Ad 强制 true、
    // isShowAd 强制 false，WebView 底部广告位（setBottomAd/setRuleAd）与开屏/结算页
    // 的 NativeAd 广告容器直接走「不显示」分支；与 block_ad_screens 互补
    // （一个拦广告 SDK 界面拉起，一个关乐跑自己的广告容器）
    if (enabled("force_close_ads")) {
        deferUntilAppReady(classLoader) {
            installed["force_close_ads"] = hookForceCloseAds(classLoader)
        }
    }

    // 20) 人脸照片替换（face_fixed_image）：默认关闭。配置 face_photo_enabled=true 且用户选过
    // 图片时，接管 Camera.takePicture 的 JPEG 回调；否则原样放行，不碰摄像头。
    installed["face_fixed_image"] = hookFacePhotoReplacement(classLoader)
    if (enabled("face_check_bypass")) installed["face_check_bypass"] = 0

    // 17) 账号会话注入：框架配置里的 lepao_session JSON 写回乐跑 MMKV 登录态
    if (enabled("session_inject")) {
        installed["session_inject"] = hookLepaoSessionInject(classLoader)
    }

    // 20) 登录深链捕获：学校/微信小程序回跳的 lptiyu:// 链接可能存在 app_token/ticket，
    // SplashActivity 的处理在 native/VMP 里，这里只记录 URI 供模块读取，不改变 App 行为。
    installed["deeplink_capture"] = hookSplashDeepLink(classLoader)

    // 18) 多账户切换器：SP「setting」+ MMKV 全量 dump/restore（账号数据、跑步目标/次数/历史）
    if (enabled("account_switcher")) {
        installed["account_switcher"] = hookAccountSwitcher(classLoader)
    }

    // 19) 微信登录凭证自动捕获：扫码/小程序授权成功后，把 quickLoginV339 回包里的
    // uid + access_token + refresh_token 落盘，App 端 root 读回即可换账号，无需再扫
    installed["wx_auto_capture"] = hookWxTokenCapture(classLoader)

    // 微信 OAuth 三元组捕获：ShareSDK 兑换/刷新 access_token 的统一解析点
    // （cn.sharesdk.wechat.utils.h 单个 String 参数的 a 方法）执行完，从 PlatformDb 读出
    // openid + access_token + refresh_token 落盘 filesDir/ls_wx_auth.json；
    // App 端 root 读回生成 lepao-wx:// 授权码二维码，没装微信的设备凭码协议直登。
    installed["wx_auth_capture"] = hookWxAuthCapture(classLoader)
    deferUntilAppReady(classLoader) { hookQqAuthCapture(classLoader) }

    // 微信扫码凭据 → 乐跑内核登录：设置 rc.a 的 openid/token，再调 p2.f 让 App 自己生成 sign/key
    deferUntilAppReady(classLoader) { bridgeWxLogin(classLoader) }

    // 14) 前后台状态固定（隐藏开关，默认开）：后台切换计数累加点在 VMP 内（w2.n1/N 转发
    // JniLib.cV），累加器本身够不到；但输入源全是 Java 方法——最后前台时间写入 no-op、
    // 读取固定"刚回前台"、PowerManager 屏幕状态恒 true，>30s 刷新缓存与热启动广告分支
    // 因条件恒不成立而被抑制，无需碰 VMP 逻辑
    if (enabled("data_bg_spoof")) {
        installed["data_bg_spoof"] = hookBackgroundState(classLoader)
    }

    // 原生加载 API 的 Hook 会改变调用者/加载器上下文，连纯日志监控也可能破坏 JNI 绑定。
    // 保留旧配置项的状态报告，但不再拦截 System 或 Runtime 的任何加载入口。
    if (enabled("native_load_monitor")) {
        installed["native_load_monitor"] = 0
        XposedBridge.log("[R7VqzN] native_load_monitor disabled: native loader APIs untouched")
    }

    // 16) 埋点上报拦截（隐藏开关，默认开）：乐跑的 v0.g 日志埋点与行为上报入口。
    // 只拦上报、不改业务逻辑；用于减少行为画像与后台统计
    if (enabled("trace_block")) {
        deferUntilAppReady(classLoader) {
            installed["trace_block"] = hookTraceReporting(classLoader)
        }
    }

    // 17) JNIUtils 本地加解密旁路（隐藏开关，默认开）：6 个 native + 3 个 VMP 转发，
    // 统一走 Java 恒等实现；不碰网络传输加密
    // 持久化与协议加密必须由宿主提供，不能用固定密钥或恒等变换替代。
    if (enabled("jni_utils_bypass")) {
        installed["jni_utils_bypass"] = 0
    }

    // 18) 广告设备指纹固定（隐藏开关，默认开）：倍孜 libdevInfo.so 的 bootMark/updateMark
    if (enabled("ad_device_info")) {
        deferUntilAppReady(classLoader) {
            installed["ad_device_info"] = hookAdDeviceInfo(classLoader)
        }
    }

    // 19) MMKV 广告开关拦截（隐藏开关，默认开）：乐跑从 MMKV 读的 14 个广告位布尔开关
    // 全部返回 false，从数据层关掉广告位（比拦 SDK 更彻底）
    if (enabled("ad_mmkv_block")) {
        deferUntilAppReady(classLoader) {
            installed["ad_mmkv_block"] = hookAdMmkvSwitches(classLoader)
        }
    }

    // 20) 乐跑用户信息导出（隐藏开关，默认开）：把 MMKV 里的 user_num/token/昵称等
    // 写到 /sdcard/Download/lepao_user.json，供 LocationSpoofer 读取（跨 UID 读不到 MMKV）
    if (enabled("lepao_user_export")) {
        deferUntilAppReady(classLoader) {
            // ABI 守卫可能跳过 native 加载；不能把未绑定的 JNI 入口当作可用数据源。
            if (!nativeSpoofLoaded) {
                // MuMu/x86 上 native 不加载，退回 Java 层 MMKV 读 hook + 广播
                installed["lepao_user_export"] = hookLepaoUserExport(classLoader)
            } else {
                installed["lepao_user_export"] = try {
                    val n = com.kdzw8p.mqa.v71f.xposed.LepaoDetectionHookerNative.exportUser()
                    XposedBridge.log("[R7VqzN] lepao user export result=$n")
                    if (n > 0) n else 0
                } catch (t: Throwable) {
                    XposedBridge.log("[R7VqzN] lepao user export failed: ${t.javaClass.simpleName}: ${t.message}")
                    0
                }
            }
        }
    }

    // 无论开关如何，Java 层导出都装一份：无 root、无 native 时靠它把用户/会话广播给模块 App
    installed["lepao_user_export_java"] = hookLepaoUserExport(classLoader)
    installed["lepao_user_export_instance"] = hookMmkvInstanceExport(classLoader)
    deferUntilAppReady(classLoader) {
        startUserExportPoller(classLoader)
    }

    // 定位错误应保留给用户和日志，不再隐藏提示或在界面分支注入成功标志。
    if (enabled("loc_fail_suppress")) {
        installed["loc_fail_suppress"] = 0
    }
}

/**
 * 隐藏开关 native_shell_spoof（默认开，不在任何配置页渲染）：
 * libDexHelper 的 C 级 fopen（/proc/self/maps、/proc/version 等）与
 * __system_property_get 不走 Java，Java hook 拦不住。这里加载模块自带的
 * libls_spoofer_native.so（源码在 xposed/native/，需 NDK 编译后打进 APK），
 * native 侧把 maps 路径重定向到 Java 层写出的过滤副本，属性命中覆写表时返回伪装值。
 * .so 未编译时静默降级（返回 0），其余 hook 不受影响。
 */
private var nativeSpoofLoaded: Boolean = false
private var nativeSpoofFailed: Boolean = false

/**
 * 隐藏开关 data_bg_spoof（默认开，不在任何配置页渲染）：把乐跑的后台切换判定链钉死。
 *
 * 机制：mb.h（生命周期回调）在 onActivityStopped 写 w2.n1(时间戳)，onActivityStarted 读
 * w2.N()，间隔 > cf.a.d0（30s）才发 vb.d0 刷新跑步缓存（JniLib 14223，VMP）；w2.L()
 * 是热启动开屏广告基准时间。累加逻辑在 VMP 内够不到，但 w2.n1/N/L 与
 * PowerManager.isInteractive/isScreenOn、UserLocalInfo.isScreenOn 都是真实 Java 方法，
 * 把输入源钉死后分支条件恒不成立：
 * - w2.n1 → no-op（最后前台时间永不更新）
 * - w2.N   → now-2s（间隔恒 <30s，vb.d0 永不触发；首次"不处理"分支也不误触）
 * - w2.L   → now-1s（热启动开屏间隔 < 阈值，广告不重放；block_ad_screens 兜底）
 * - PowerManager.isInteractive / isScreenOn → true（屏幕状态恒为亮屏）
 * - UserLocalInfo.isScreenOn → true（实体读取恒为亮屏）
 */
private fun hookBackgroundState(classLoader: ClassLoader): Int {
    var hooked = 0

    val w2 = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.w2", classLoader)
    if (w2 != null) {
        // 写侧 no-op：固定 w2.n1，最后前台时间戳永不更新
        try {
            XposedHelpers.module.hook(w2.getDeclaredMethod("n1", java.lang.Long.TYPE)).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = null
                }
            )
            hooked++
        } catch (_: Throwable) {
        }
        // 读侧固定：w2.N() 恒为"2 秒前刚回前台"，30s 阈值分支永远不成立
        try {
            XposedHelpers.module.hook(w2.getDeclaredMethod("N")).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return System.currentTimeMillis() - 2000L
                    }
                }
            )
            hooked++
        } catch (_: Throwable) {
        }
        // 热启动开屏广告基准时间：间隔恒 < 阈值，广告不重放
        try {
            XposedHelpers.module.hook(w2.getDeclaredMethod("L")).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        return System.currentTimeMillis() - 1000L
                    }
                }
            )
            hooked++
        } catch (_: Throwable) {
        }
    }

    // PowerManager 屏幕状态：isInteractive（API21+）与 isScreenOn 恒 true，
    // 目标进程内所有前台/亮屏判定（ib.b.k、kwad ay）读到"亮屏前台"
    try {
        val pm = Class.forName("android.os.PowerManager")
        for (m in pm.declaredMethods) {
            if (Modifier.isNative(m.modifiers)) continue
            if ((m.name == "isInteractive" || m.name == "isScreenOn") &&
                m.returnType == java.lang.Boolean.TYPE
            ) {
                try {
                    XposedHelpers.module.hook(m).intercept(
                        object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? = true
                        }
                    )
                    hooked++
                } catch (_: Throwable) {
                }
            }
        }
    } catch (_: Throwable) {
    }

    // GreenDao 实体：isScreenOn 读取恒 true（写入方在 VMP 内，改不了；读侧钉死即可）
    try {
        val uli = XposedHelpers.findClassIfExists(
            "com.lptiyu.tanke.entity.greendao.UserLocalInfo", classLoader
        )
        uli?.declaredMethods?.firstOrNull { it.name == "isScreenOn" }?.let { m ->
            XposedHelpers.module.hook(m).intercept(
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = true
                }
            )
            hooked++
        }
    } catch (_: Throwable) {
    }
    return hooked
}


private fun loadNativeShellSpoof(classLoader: ClassLoader): Int {
    if (nativeSpoofLoaded) return 1
    if (nativeSpoofFailed) return 0
    // MuMu 等模拟器是 x86_64 + ARM 转译（houdini）：arm64 .so 在转译层对 libc 做
    // 内联 hook，壳层自检 3s 内即 SIGSEGV/SI_TKILL 自毁（tombstone 实证）。
    // 主 ABI 是 x86 系一律跳过；真机 arm64 才启用
    if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) {
        XposedBridge.log(
            "[R7VqzN] native shell spoof skipped: x86 translation env " +
                android.os.Build.SUPPORTED_ABIS.contentToString()
        )
        return 0
    }
    try {
        val filter = readMapsFiltered("/proc/self/maps")
        // maps 副本与覆写表都必须落在目标 uid 可写的目录：createTempFile 无目录参数时走
        // java.io.tmpdir（Android 上未设置，cwd="/"），目标进程里会抛 EACCES —— 实测日志
        // 显示 Permission denied。改用目标 app 的 cacheDir（模块代码运行在乐跑进程内，
        // cacheDir 即 /data/user/0/<pkg>/cache，本 uid 可写）。ActivityThread 是 hidden API，
        // 编译期直接引用在部分 stub 集里 unresolved，走反射；取不到时兜底硬编码目标包目录
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? android.app.Application
        val dir = app?.cacheDir ?: java.io.File("/data/data/com.lptiyu.tanke/cache")
        val pid = android.os.Process.myPid()
        val mapsFile = if (filter != null) {
            val f = java.io.File(dir, "ls_maps_$pid.txt")
            f.writeText(filter)
            f.deleteOnExit()
            f
        } else null        // 覆写表落盘：行格式 key=value，native hook 解析后在属性命中时返回对应值
        val lines = (basePropertyOverrides +
            (if (looksLikeEmulator()) devicePropertyOverrides else emptyMap<String, String>()))
            .map { (k, v) -> "$k=$v" }
        val propsFile = java.io.File(dir, "ls_props_$pid.txt")
        propsFile.writeText(lines.joinToString("\n") + "\n")
        System.loadLibrary("ls_spoofer_native")
        // 模块代码由 LSPosed 模块加载器（非目标 app 的 classLoader）装载，
        // 桥类要用模块自己的 classLoader 查找（即调用方所在加载器）
        val cls = Class.forName("com.kdzw8p.mqa.v71f.xposed.LepaoDetectionHookerNative")
        cls.getMethod("configure", String::class.java, String::class.java)
            .invoke(null, mapsFile?.absolutePath ?: "", propsFile.absolutePath)
        nativeSpoofLoaded = true
        XposedBridge.log("[R7VqzN] native shell spoof loaded (fopen + prop hooks armed)")
        return 1
    } catch (e: Throwable) {
        nativeSpoofFailed = true
        XposedBridge.log("[R7VqzN] native shell spoof failed: ${e.javaClass.simpleName}: ${e.message}")
        return 0
    }
}

/**
 * 把某个类里所有 boolean / Boolean 返回值的方法改成固定返回 false。
 *
 * 用「遍历 declaredMethods 再按返回类型过滤」而不是逐个写方法名，是因为这些检测类
 * 在版本迭代里会加探针（RootBeer 24 个、kwad 每组 2~4 个），按名字硬编码会漏掉新增项；
 * predicate 用来把范围收窄到具体方法，避免误伤同名的业务方法。
 */
private fun hookBooleanMethods(
    clazz: Class<*>,
    predicate: (Method) -> Boolean
): Int {
    var hooked = 0
    for (m in clazz.declaredMethods) {
        if (Modifier.isAbstract(m.modifiers)) continue
        // native 方法不能被 libxposed 调原方法（参数数组传 null 会让 ART 在 GetArrayLength 处 abort）
        if (Modifier.isNative(m.modifiers)) continue
        if (!predicate(m)) continue
        val returnType = m.returnType
        val isPrimitive = returnType == java.lang.Boolean.TYPE
        val isBoxed = returnType == java.lang.Boolean::class.java
        if (!isPrimitive && !isBoxed) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return if (isPrimitive) false else java.lang.Boolean.FALSE
                }
            })
            hooked++
        } catch (_: Throwable) {
            // 单个方法挂不上不影响其余探针
        }
    }
    return hooked
}

/**
 * kwad 的每个检查组（s$a / s$d / s$e）把实际检查写在匿名类里：
 * com.kwad.sdk.utils.s$a$1 / $2 …，它们不是嵌套类，Class.getDeclaredClasses 看不到，
 * 只能按匿名类命名规则逐个 loadClass 再挂 cG。
 */
private fun hookKwadSubChecks(classLoader: ClassLoader, outerClassName: String): Int {
    var hooked = 0
    for (i in 1..8) {
        val anonymous = XposedHelpers.findClassIfExists("${outerClassName}$i", classLoader) ?: continue
        hooked += hookBooleanMethods(anonymous) { m -> m.name == "cG" }
    }
    return hooked
}

/**
 * 模拟器会创建 /dev/socket/qemud 与 /dev/qemu_pipe 两个设备节点，q0.c 用 File.exists 判断。
 * 这两个路径在真机上不存在，因此只对这两个精确路径返回 false，不影响任何其它文件读取。
 */
private fun hookQemuDevicePaths(): Int {
    val qemuPaths = setOf("/dev/socket/qemud", "/dev/qemu_pipe")
    var hooked = 0
    try {
        XposedHelpers.hookAllMethods(java.io.File::class.java, "exists") { chain, _ ->
            val path = (chain.thisObject as? java.io.File)?.path
            if (path != null && qemuPaths.contains(path)) {
                return@hookAllMethods false
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        hooked++
    } catch (_: Throwable) {
    }
    return hooked
}

/** Build 字段伪装值：与 devicePropertyOverrides 同一套三星 S20 指纹 */
private val buildFieldSpoofs = mapOf(
    "MANUFACTURER" to "samsung",
    "BRAND" to "samsung",
    "MODEL" to "SM-G9810",
    "DEVICE" to "r8s",
    "HARDWARE" to "pineapple",
    "FINGERPRINT" to "samsung/r8sxaid/r8s:11/RP1A.200728.012/G9810ZCUKFGCE:release-keys"
)

/** 模拟器标记词：原始 Build 值命中任一即判定在模拟器上，才改写；普通手机原样保留 */
private val EMULATOR_MARKERS = listOf(
    "ttvm", "nemu", "mumu", "bluestacks", "genymotion", "nox", "ldmn",
    "goldfish", "ranchu", "qemu", "virtio", "droid4x", "vboxsf", "android_emulator"
)

/**
 * 用 Build 的原始值（HARDWARE / FINGERPRINT / MODEL / MANUFACTURER）判断是否在模拟器上。
 * 必须在 spoofBuildFields 写入 Build 字段之前调用，否则原始值已被覆写成三星，
 * 判断失效。
 */
private fun looksLikeEmulator(): Boolean {
    val values = try {
        val b = Class.forName("android.os.Build")
        listOf("HARDWARE", "FINGERPRINT", "MODEL", "MANUFACTURER", "DEVICE")
            .mapNotNull { f -> (b.getField(f).get(null) as? String)?.lowercase() }
    } catch (_: Throwable) {
        return false
    }
    return values.any { v -> EMULATOR_MARKERS.any { m -> v.contains(m) } }
}

/**
 * 改 android.os.Build 的 static final 字段：Xposed 环境里 Build 字段是 native 填充的
 * static String，XposedHelpers.setStaticFieldValue 直接覆盖；乐跑进程内所有读 Build.MODEL 的
 * 代码（kwad 模拟器检测、壳 4.7 指纹表）拿到的都是伪装值，真机系统不受影响。
 * 普通手机 active=false，保留真实指纹，不给交叉校验（Build vs native 属性 / build.prop）
 * 留下不一致点。
 */
private fun spoofBuildFields(active: Boolean): Int {
    if (!active) {
        XposedBridge.log("[R7VqzN] device_spoof: real device, skipping Build rewrite")
        return 0
    }
    val build = try {
        Class.forName("android.os.Build")
    } catch (_: Throwable) {
        return 0
    }
    var done = 0
    for ((field, value) in buildFieldSpoofs) {
        try {
            XposedHelpers.setStaticFieldValue(build.getDeclaredField(field), value)
            done++
        } catch (_: Throwable) {
        }
    }
    return done
}

/** 基础反模拟器键：模拟器与真机都生效，普通手机上这些值本来就该是这个，无不一致风险 */
private val basePropertyOverrides = linkedMapOf(
    "ro.kernel.qemu" to "",
    "ro.secure" to "1",
    "ro.debuggable" to "0",
    "ro.build.tags" to "release-keys"
)

/** 设备身份键：仅在模拟器上（detect_device_spoof 开关开 且 looksLikeEmulator 为真）改写。
 * 普通手机 native 侧 __system_property_get / build.prop 都是真值，Java 侧写假值
 * 是交叉校验唯一能抓到的不一致点，所以真机一律不动 */
private val devicePropertyOverrides = linkedMapOf(
    // 设备身份伪装（detect_device_spoof）：仿三星 S20，与 build.prop 一致
    "ro.product.manufacturer" to "samsung",
    "ro.product.brand" to "samsung",
    "ro.product.model" to "SM-G9810",
    "ro.product.device" to "r8s",
    "ro.build.fingerprint" to "samsung/r8sxaid/r8s:11/RP1A.200728.012/G9810ZCUKFGCE:release-keys",
    "ro.product.build.fingerprint" to "samsung/r8sxaid/r8s:11/RP1A.200728.012/G9810ZCUKFGCE:release-keys",
    "ro.product.board" to "R8A",
    "ro.board.platform" to "pineapple",
    "ro.boot.bootdevice" to "soc.0",
    "ro.boot.baseband" to "11.0",
    "gsm.version.baseband" to "G9810ZCUKFGCE"
)

/** 广告 SDK 界面包前缀（来自乐跑 4.7 清单）：快手 kwad、字节 Pangle/msdk/直播插件、益方 yfanads、倍孜 beizi */
private val AD_SCREEN_PREFIXES = listOf(
    "com.kwad.sdk.",
    "com.kwad.auth.",
    "com.bytedance.sdk.openadsdk.",
    "com.bytedance.msdk.",
    "com.bytedance.android.openliveplugin.",
    "com.yfanads.",
    "com.beizi.ad."
)

/** 开屏广告视图树探针：挂在安全的 onCreate 里，延迟走查装饰视图类名；不 hook 任何 native 方法 */
private fun dumpSplashViewTreeLater(activity: android.app.Activity) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching {
            val decor = activity.window?.decorView ?: return@runCatching
            val names = LinkedHashSet<String>()
            fun walk(v: android.view.View, depth: Int) {
                if (depth > 6) return
                names.add(v.javaClass.name)
                (v as? android.view.ViewGroup)?.let { vg ->
                    for (i in 0 until vg.childCount) runCatching { walk(vg.getChildAt(i), depth + 1) }
                }
            }
            walk(decor, 0)
            XposedBridge.log("[R7VqzN] splash view tree: " + names.joinToString(","))
        }
    }, 6000)
}

/** 开屏广告自动跳过：活动 resume + 新窗口 addView 双路轮询，代点跳过按钮（不限定 SplashActivity，广告可能在独立窗口） */
private fun LocationHooker.registerSplashAdSkipper(classLoader: ClassLoader) {
    val app = currentApp() ?: return
    try {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                activity.window?.decorView?.let { startSkipPoller(it, "activity:${activity.javaClass.simpleName}") }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // 对话/弹窗级广告窗口不走 Activity 回调：任何新窗口视图树加入时同样轮询代点
        runCatching {
            val wmi = XposedHelpers.findClassIfExists("android.view.WindowManagerImpl", classLoader)
            if (wmi != null) {
                XposedHelpers.hookAllMethods(wmi, "addView") { chain, _ ->
                    val added = chain.args.getOrNull(0) as? android.view.View
                    val res = chain.proceed(chain.args.toTypedArray())
                    if (added != null) startSkipPoller(added, "window")
                    res
                }
            }
        }
        XposedBridge.log("[R7VqzN] splash ad skipper registered")
    } catch (t: Throwable) {
        XposedBridge.log("[R7VqzN] splash ad skipper failed: ${t.message}")
    }
}

/** 在给定视图根上轮询最多 6 秒：命中乐跑跳过按钮即 performClick（等同用户手点） */
private fun startSkipPoller(root: android.view.View, tag: String) {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var ticks = 0
    val poll = object : Runnable {
        override fun run() {
            ticks++
            var skip: android.view.View? = runCatching {
                val id = root.resources.getIdentifier("tev_countdown", "id", "com.lptiyu.tanke")
                if (id != 0) clickableAncestor(root.findViewById<android.view.View>(id)) else null
            }.getOrNull()
            if (skip == null) skip = findSkipViewByView(root)
            if (skip != null) {
                runCatching { skip.performClick() }
                XposedBridge.log("[R7VqzN] splash ad auto-skipped via $tag")
                return
            }
            if (ticks < 30) handler.postDelayed(this, 300)
        }
    }
    handler.postDelayed(poll, 200)
}

/** tev_countdown 可能是内层 TextView，点击监听在外层可点击容器上；向上找直到可点击根。 */
private fun clickableAncestor(view: android.view.View?): android.view.View? {
    var current = view ?: return null
    var depth = 0
    while (depth++ < 5) {
        if (current.isClickable) return current
        current = current.parent as? android.view.View ?: return null
    }
    return null
}

/** 资源 id 失效时的兜底：走查视图树找文案含「跳过」或形如 2S 的可点 TextView */
private fun findSkipViewByView(root: android.view.View?): android.view.View? {
    if (root == null) return null
    if (root is android.view.ViewGroup) {
        for (i in 0 until root.childCount) {
            findSkipViewByView(root.getChildAt(i))?.let { return it }
        }
        return null
    }
    (root as? android.widget.TextView)?.let { tv ->
        val t = tv.text?.toString().orEmpty()
        // 实测按钮文案 = "2S | 跳过" 或 "2S"，不能只匹配 ^\\d+S$
        if (t.isNotBlank() && (t.contains("跳过") || Regex("^\\d+S").containsMatchIn(t.trim()))) {
            return clickableAncestor(root) ?: root
        }
    }
    return null
}

/** 判断一个 Intent 是否指向广告 SDK 的界面（component 或 package 命中任一前缀） */
private fun isAdScreenIntent(intent: Any?): Boolean {
    val i = intent as? android.content.Intent ?: return false
    val comp = runCatching { i.component }.getOrNull()
    if (comp != null && AD_SCREEN_PREFIXES.any { comp.className.startsWith(it) || comp.packageName.startsWith(it) }) return true
    val pkg = runCatching { i.getPackage() }.getOrNull()
    return pkg != null && AD_SCREEN_PREFIXES.any { pkg.startsWith(it) }
}

/**
 * 广告屏拦截（block_ad_screens）：hook Context/Activity 的 startActivity，Intent 指向广告 SDK 界面时
 * 不继续调用（void 方法直接返回），广告弹层不再出现。选在 startActivity 这一层而不是各 SDK
 * 的 load/show 入口，是因为乐跑接了 4 家广告 SDK，逐家找入口太脆；组件名单来自 4.7 清单，
 * 只影响界面拉起，广告请求/计时等业务逻辑原样保留，主流程不受影响。
 */
private fun hookAdScreens(classLoader: ClassLoader): Int {
    var hooked = 0
    for (name in listOf("android.content.Context", "android.app.Activity")) {
        val c = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
        for (m in c.declaredMethods) {
            if (m.name != "startActivity") continue
            try {
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val intent = chain.args.getOrNull(0)
                        if (isAdScreenIntent(intent)) {
                            XposedBridge.log(
                                "[R7VqzN] ad screen blocked: " +
                                    runCatching { (intent as? android.content.Intent)?.component?.flattenToShortString() }.getOrNull() ?: "?"
                            )
                            return Unit
                        }
                        return chain.proceed(chain.args.toTypedArray())
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }
    return hooked
}

// 核验入口与相机输入保持宿主实现，不属于定位模拟的数据变换。
/**
 * 账号会话注入（session_inject）：把框架配置里的 "lepao_session" 对象
 * {"user_id":"…","token":"…","role":"0",…} 写进乐跑自己的 MMKV（c2，实例名
 * "setting"，key 名与 rc.f 常量表一致），等价于登录态——乐跑启动时 rc.a.x()/e()
 * 直接拿到 uid/token，跳过微信/小程序那一步。MMKV 实例任何时刻创建（app 进程内
 * c2.b() 初始化、多进程副本）都触发一次写入；配置为空时 no-op。
 * 登录态 key 的类型与 rc.a.V0(UserDataEntity) 写入时一致：user_id/refresh_expire
 * 是 long，role/admin 是 int，其余为 string。
 */
/**
 * MMKV 广告开关拦截（ad_mmkv_block，默认开）：
 * 乐跑从 MMKV 实例 "setting" 读一批服务端下发的广告开关（is_ad_main / cold_open_adv /
 * is_ad_bottom / is_show_circle / is_rule_ad ... 共 14 个）。这些开关是布尔值，
 * 只要 decodeBool / getBoolean 命中这些 key 就返回 false，广告位直接从数据层关掉，
 * 比拦 SDK 界面更彻底（不依赖 SDK 类名、不会因 SDK 升级失效）。
 * 只改广告类 key，不碰登录态、跑步数据、检测开关。
 */
private val AD_MKV_BOOL_KEYS = setOf(
    "is_ad_main", "is_ad_hot", "is_ad_run", "is_index_adv",
    "is_show_circle", "is_ad_banner", "is_ad_bottom", "is_ad_shop",
    "is_rule_ad", "is_achievement_ad", "is_circle_ad", "is_game_ad",
    "ad_sdk_is_on", "is_ad_video"
)

/** 前缀匹配：cold_open_adv / hot_open_adv 带前后缀变体 */
private fun isAdSwitchKey(key: String): Boolean =
    key in AD_MKV_BOOL_KEYS || key.startsWith("is_ad_") || key.endsWith("_adv") ||
        key.endsWith("_ad") && key.startsWith("is_")

private fun hookAdMmkvSwitches(classLoader: ClassLoader): Int {
    val mmkv = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", classLoader) ?: return 0
    var hooked = 0
    // instance 方法：decodeBool(String, boolean) / getBoolean(String, boolean)
    for (m in mmkv.declaredMethods) {
        if (Modifier.isNative(m.modifiers)) continue
        val n = m.name
        if (n != "decodeBool" && n != "getBoolean") continue
        if (m.parameterCount != 2) continue
        if (m.parameterTypes[0] != String::class.java) continue
        if (m.parameterTypes[1] != java.lang.Boolean.TYPE) continue
        if (m.returnType != java.lang.Boolean.TYPE) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val key = chain.args.getOrNull(0) as? String
                    if (key != null && isAdSwitchKey(key)) {
                        return false
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    // 静态变体：MMKV.decodeBool(String, boolean)（部分版本提供）
    for (m in mmkv.declaredMethods) {
        if (Modifier.isNative(m.modifiers)) continue
        if (!Modifier.isStatic(m.modifiers)) continue
        if (m.name != "decodeBool" && m.name != "getBoolean") continue
        if (m.parameterCount != 2) continue
        if (m.parameterTypes[0] != String::class.java) continue
        if (m.returnType != java.lang.Boolean.TYPE) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val key = chain.args.getOrNull(0) as? String
                    if (key != null && isAdSwitchKey(key)) return false
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

/**
 * 乐跑用户信息导出（lepao_user_export，默认开）：
 * LocationSpoofer 运行在 u0_a66，无权读乐跑（u0_a69）的 MMKV 文件。
 * 本模块注入乐跑进程（u0_a69）后，在 MMKV 实例创建时读一遍关键字段，
 * 写到 /sdcard/Android/data/com.kdzw8p.mqa.v71f/files/lepao_user.json，
 * LocationSpoofer 再从该文件读取。
 */
private const val LEPAO_USER_EXPORT_DIR = "/sdcard/Download"
private const val LEPAO_USER_EXPORT_FILE = "lepao_user.json"

@Volatile
private var lepaoMmkvInstance: Any? = null

private fun registerUserExportReceiver(classLoader: ClassLoader) {
    // attach 阶段 Application 可能还没建好：currentApplication() 为 null 时每秒重试，
    // 保证 SWITCH_USER 回执通道在开屏结束前就绑上
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var attempts = 0
    val waiter = object : Runnable {
        override fun run() {
            attempts++
            val app = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.app.Application
            }.getOrNull()
            if (app == null) {
                if (attempts < 30) handler.postDelayed(this, 1000)
                return
            }
            registerUserExportReceiverOn(app, classLoader)
        }
    }
    handler.post(waiter)
}

private fun registerUserExportReceiverOn(app: android.app.Application, classLoader: ClassLoader) {
    try {
        val filter = android.content.IntentFilter("com.kdzw8p.mqa.v71f.REQUEST_USER")
        filter.addAction("com.kdzw8p.mqa.v71f.SWITCH_USER")
        filter.addAction("com.kdzw8p.mqa.v71f.RENEW_SESSION")
        filter.addAction("com.kdzw8p.mqa.v71f.REQUEST_RULES")
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.kdzw8p.mqa.v71f.RENEW_SESSION") {
                    val process = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentProcessName") as? String
                    if (process != app.packageName) return
                    @Suppress("DEPRECATION")
                    val proof = intent.getParcelableExtra<android.app.PendingIntent>("proof") ?: return
                    val moduleUid = runCatching { app.packageManager.getApplicationInfo(MODULE_PKG, 0).uid }.getOrNull() ?: return
                    if (proof.creatorPackage != MODULE_PKG || proof.creatorUid != moduleUid) return
                    val session = runCatching { org.json.JSONObject(intent.getStringExtra("session").orEmpty()) }.getOrNull() ?: return
                    val previous = intent.getStringExtra("previous_token").orEmpty()
                    runCatching {
                        val account = Class.forName("rc.a", false, classLoader)
                        val uid = (account.getMethod("x").invoke(null) as Number).toLong().toString()
                        val token = account.getMethod("e").invoke(null) as? String ?: ""
                        val next = session.optString("token").ifBlank { session.optString("access_token") }
                        if (uid != session.optString("user_id") || token != previous || next.isBlank()) return
                        val params = Class.forName("kd.h", false, classLoader)
                        val store = Class.forName("com.lptiyu.tanke.utils.c2", false, classLoader)
                        // 只替换同UID凭据，不调用V0，不清空学校、跑步缓存或导航页面。
                        XposedHelpers.callStaticMethod(store, "a", "access_token", next)
                        XposedHelpers.callStaticMethod(store, "a", "token", next)
                        for (key in listOf("refresh_token", "refresh_expire")) {
                            if (session.optString(key).isNotBlank()) XposedHelpers.callStaticMethod(store, "a", key, session.optString(key))
                        }
                        params.getDeclaredField("a").apply { isAccessible = true }.set(null, next)
                        XposedHelpers.callStaticMethod(store, "d")
                        (lepaoMmkvInstance ?: mmkvSettingInstance(classLoader))?.let { exportLepaoUser(it) }
                    }
                    return
                }
                if (intent?.action == "com.kdzw8p.mqa.v71f.REQUEST_RULES") {
                    LepaoRuleReader.read(app, classLoader, intent)
                    return
                }
                if (intent?.action == "com.kdzw8p.mqa.v71f.SWITCH_USER") {
                    val process = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentProcessName") as? String
                    if (process != app.packageName) return
                    val switchRequestId = intent.getStringExtra("request_id").orEmpty()
                    val sw = intent.getStringExtra("json")
                        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    if (sw == null) {
                        broadcastWxResult("error", "切换广播缺 json 或解析失败", switchRequestId)
                        return
                    }
                    // V0 内部写 SP/MMKV 且可能触 UI 缓存，统一回主线程执行
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val uid = sw.optString("user_id")
                        if (uid.isBlank() || uid.toLongOrNull() == 0L || sw.optString("access_token").isBlank()) {
                            broadcastWxResult("error", "切换凭据不完整（user_id/access_token 空或 uid=0）", switchRequestId)
                            return@post
                        }
                        val err = applyLepaoSwitch(classLoader, sw).ifEmpty {
                            // 回执证明宿主实际使用的身份已切换，不只证明反射方法返回。
                            val valid = runCatching {
                                val account = Class.forName("rc.a", false, classLoader)
                                (account.getMethod("x").invoke(null) as Number).toLong().toString() == uid &&
                                    account.getMethod("e").invoke(null) == sw.optString("access_token")
                            }.getOrDefault(false)
                            if (valid) "" else "写回后的会话校验失败"
                        }
                        if (err.isEmpty()) {
                            (lepaoMmkvInstance ?: mmkvSettingInstance(classLoader))?.let { exportLepaoUser(it) }
                            broadcastWxResult("ok", "切换写回成功：已按 rc.a.V0 链路写入 uid=$uid 的登录态", switchRequestId)
                        } else {
                            XposedBridge.log("[R7VqzN] switch failed: $err")
                            broadcastWxResult("error", "切换写回失败：$err", switchRequestId)
                        }
                    }
                    return
                }
                val mmkv = lepaoMmkvInstance ?: mmkvSettingInstance(classLoader)
                if (mmkv != null) exportLepaoUser(mmkv)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        XposedBridge.log("[R7VqzN] lepao user export receiver registered")
    } catch (t: Throwable) {
        XposedBridge.log("[R7VqzN] lepao user export receiver failed: ${t.javaClass.simpleName}: ${t.message}")
    }
}

/** 需要导出的 key（与实测 MMKV 字段名一致） */
private val LEPAO_USER_EXPORT_KEYS = listOf(
    "user_num", "user_id", "token", "user_access_token", "access_token",
    "refresh_token", "refresh_expire", "user_nickname", "user_name",
    "user_phone", "user_sex", "school_name", "school_id", "auth_num",
    "role", "role_num", "user_openId", "student_status", "term_id", "course_id",
    "user_avatar_url", "run_rule"
)

private fun startUserExportPoller(classLoader: ClassLoader) {
    Thread {
        repeat(12) {
            try {
                val mmkv = lepaoMmkvInstance ?: mmkvSettingInstance(classLoader)
                if (mmkv != null) exportLepaoUser(mmkv)
            } catch (_: Throwable) {
            }
            Thread.sleep(5000)
        }
    }.start()
}

/** 这个打包版 MMKV 没有 mmkvWithID(String) 单参重载，按参数表挑最少参数的静态工厂凑默认值调用 */
private fun mmkvSettingInstance(classLoader: ClassLoader): Any? = runCatching {
    val cls = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", classLoader) ?: return@runCatching null
    val m = cls.declaredMethods
        .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("mmkvWith") }
        .minByOrNull { it.parameterCount } ?: return@runCatching null
    val args = m.parameterTypes.map { t ->
        when (t) {
            String::class.java -> "setting"
            Int::class.javaPrimitiveType -> 1
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }.toTypedArray()
    m.invoke(null, *args)
}.getOrNull()

private var lepaoExportRunning = false

private fun exportLepaoUser(mmkv: Any, authorizationRequestId: String = "") {
    // getString 可能被 hook 且 hook 回调用导出，不防重入会无限递归栈溢出
    if (lepaoExportRunning) return
    lepaoExportRunning = true
    var lastErr: Throwable? = null
    try {
        val dir = java.io.File(LEPAO_USER_EXPORT_DIR)
        if (!dir.exists()) dir.mkdirs()
        val json = org.json.JSONObject()
        for (k in LEPAO_USER_EXPORT_KEYS) {
            val v = try {
                // 这个打包版 MMKV 只有 getString，没有 decodeString
                mmkv.javaClass.getMethod("getString", String::class.java, String::class.java)
                    .invoke(mmkv, k, "")
            } catch (t: Throwable) {
                lastErr = t
                null
            }
            if (v is String && v.isNotBlank()) json.put(k, v)
        }
        if (json.length() == 0) {
            val methods = mmkv.javaClass.methods.map { it.name }.distinct().filter { it.contains("tring") || it.contains("decode") }
            XposedBridge.log("[Xx9QKv] lepao user export empty; err=${lastErr?.javaClass?.simpleName}: ${lastErr?.message}; methods=$methods")
            return
        }
        // user_id / user_num / auth_num / school_id 在 MMKV 里可能是 varint，decodeString 取不到
        for (k in listOf("user_id", "user_num", "auth_num", "school_id")) {
            if (json.has(k)) continue
            val n = runCatching {
                mmkv.javaClass.getMethod("getLong", String::class.java, java.lang.Long.TYPE)
                    .invoke(mmkv, k, 0L) as? Long ?: 0L
            }.getOrDefault(0L)
            if (n > 0L) json.put(k, n.toString())
        }
        // 内存会话可能早于 MMKV 更新；与原 App 发请求的 rc.a.e/x 使用同一所有者。
        val account = Class.forName("rc.a", false, mmkv.javaClass.classLoader)
        val uid = (account.getMethod("x").invoke(null) as? Number)?.toLong() ?: 0L
        val token = account.getMethod("e").invoke(null) as? String ?: ""
        if (uid <= 1L || token.isBlank()) return
        json.put("user_id", uid.toString())
        json.put("token", token)
        json.put("access_token", token)
        // 只读接口的签名绑定宿主设备上下文；导出一次后模块可独立重建请求，无需宿主常驻。
        runCatching {
            val loader = mmkv.javaClass.classLoader
            val device = Class.forName("rc.b", false, loader)
            val store = Class.forName("com.lptiyu.tanke.utils.c2", false, loader)
            val umid = XposedHelpers.callStaticMethod(store, "i", "umid", "") as? String ?: ""
            val deviceId = XposedHelpers.callStaticMethod(device, "p") as? String ?: ""
            if (umid.isNotBlank() && deviceId.isNotBlank()) {
                json.put("rule_context", org.json.JSONObject()
                    .put("umid", umid).put("mobileDeviceId", deviceId)
                    .put("mobileModel", XposedHelpers.callStaticMethod(device, "h"))
                    .put("mobileOsVersion", XposedHelpers.callStaticMethod(device, "j"))
                    .put("version", XposedHelpers.callStaticMethod(device, "q").toString())
                    .put("version_name", XposedHelpers.callStaticMethod(device, "r")))
            }
        }
        if (authorizationRequestId.isNotBlank()) json.put("authorization_request_id", authorizationRequestId)
        json.put("exportedAt", System.currentTimeMillis())
        // 设备上下文只通过模块私有接收通道传递，不写入共享目录的资料快照。
        val publicSnapshot = org.json.JSONObject(json.toString()).apply { remove("rule_context") }
        runCatching { java.io.File(dir, LEPAO_USER_EXPORT_FILE).writeText(publicSnapshot.toString()) }
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
            app?.sendBroadcast(
                android.content.Intent("com.kdzw8p.mqa.v71f.LEPAO_USER")
                    .setPackage("com.kdzw8p.mqa.v71f")
                    .putExtra("json", json.toString())
            )
        }
        XposedBridge.log("[Xx9QKv] lepao user exported: keys=${json.length()}")
    } catch (t: Throwable) {
        XposedBridge.log("[Xx9QKv] lepao user export failed: ${t.javaClass.simpleName}: ${t.message}")
    } finally {
        lepaoExportRunning = false
    }
}

/**
 * 乐跑用户信息导出（lepao_user_export）：
 * 关键：不能 hook MMKV.mmkvWith*——延迟挂载时实例早就创建完了，hook 永不触发（实测三轮都没导出）。
 * 改 hook decodeString：只要乐跑读 user_num / user_access_token 就触发一次导出，
 * 不依赖实例创建时机，且读取时实例必然存在。
 */
internal fun LocationHooker.hookLepaoUserExport(classLoader: ClassLoader): Int {
    val mmkv = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", classLoader) ?: return 0
    var hooked = 0
    // 不猜具体 API：MMKV 所有读方法全部挂上，任一命中用户字段就导出。
    // decodeString/getString/decodeInt/getInt/decodeLong/getLong/decodeBool/getBoolean 都要盖。
    val readMethods = setOf(
        "decodeString", "getString", "decodeInt", "getInt",
        "decodeLong", "getLong", "decodeBool", "getBoolean",
        "decodeDouble", "getDouble", "decodeFloat", "getFloat"
    )
    for (m in mmkv.declaredMethods) {
        if (m.name !in readMethods) continue
        if (m.parameterCount < 1) continue
        // decodeString/decodeInt 等是 MMKV 的 JNI 实现：libxposed 调 native 原方法时拿不到参数数组，
        // ART 在 GetArrayLength(null) 处直接 abort（实测切入账号后必现）。只 hook Java 封装层。
        if (Modifier.isNative(m.modifiers)) continue
        if (m.parameterTypes[0] != String::class.java) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val key = chain.args.getOrNull(0) as? String
                    val result = chain.proceed(chain.args.toTypedArray())
                    if (key != null && (key == "user_num" || key == "user_access_token" ||
                                key == "user_id" || key == "token" || key == "access_token" || key == "run_rule")) {
                        val self = chain.thisObject ?: runCatching {
                            mmkv.getMethod("defaultMMKV").invoke(null)
                        }.getOrNull()
                        if (self != null) {
                            lepaoMmkvInstance = self
                            exportLepaoUser(self)
                        }
                    }
                    return result
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    XposedBridge.log("[R7VqzN] lepao user export hooks installed: $hooked")
    return hooked
}

private fun hookMmkvInstanceExport(classLoader: ClassLoader): Int {
    val mmkv = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", classLoader)
    if (mmkv == null) {
        XposedBridge.log("[R7VqzN] mmkv class NOT FOUND in target classloader")
        return 0
    }
    var hooked = 0
    for (m in mmkv.declaredMethods) {
        if (Modifier.isNative(m.modifiers)) continue
        if (!Modifier.isStatic(m.modifiers) || !m.name.startsWith("mmkvWith")) continue
        try {
            XposedHelpers.module.hook(m).intercept { chain ->
                val result = chain.proceed(chain.args.toTypedArray())
                if (result != null) {
                    lepaoMmkvInstance = result
                    runCatching { exportLepaoUser(result) }
                }
                result
            }
            hooked++
        } catch (_: Throwable) {
        }
    }
    // App 在 Application 初始化时就建好 setting 实例，早于 hook 安装，
    // 静态工厂之后不会再走 hook；这里延迟直接反射拿现存实例导一次，
    // 否则广播桥永远建不起来，界面拿不到真实 uid/token/run_rule。
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        val cls = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", classLoader)
        XposedBridge.log("[R7VqzN] mmkv direct export: class=${cls != null}")
        runCatching {
            val inst = mmkvSettingInstance(classLoader) ?: lepaoMmkvInstance
            XposedBridge.log("[R7VqzN] mmkv direct export: inst=${inst != null}")
            if (inst != null) {
                lepaoMmkvInstance = inst
                exportLepaoUser(inst)
            }
        }.onFailure {
            XposedBridge.log("[R7VqzN] mmkv direct export failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }, 5000)
    return hooked
}

/** 登录态只由显式切换和同账号续期写回；MMKV 工厂回调不得反复重放配置旧票。 */
internal fun LocationHooker.hookLepaoSessionInject(classLoader: ClassLoader): Int = 0

/**
 * 多账户切换器（account_switcher）：乐跑的全部账号数据（登录态、跑步目标/次数/
 * 历史等）落在两块——SharedPreferences「setting」（c2 双写一半）与 MMKV（另一半）。
 * 在 RunApplication.getInstance（每进程一次、任何业务请求之前）做两件事：
 * - 配置 lepao_snapshot 非空 → 把目标账户的全量快照写回 SP+MMKV（切换用户，重启后生效）；
 * - 配置 lepao_dump_request 非空且 dump 文件尚不存在 → 把当前账户全量 key-value
 *   dump 到 filesDir/ls_backup_<name>.json，App 端用 root 读走存档。
 * dump 文件存在即跳过，保证幂等；restore 与 dump 都在同一时机，不会出现半写状态。
 */
internal fun LocationHooker.hookAccountSwitcher(classLoader: ClassLoader): Int {
    val runApp = XposedHelpers.findClassIfExists(
        "com.lptiyu.tanke.application.RunApplication", classLoader
    ) ?: return 0
    var hooked = 0
    for (m in runApp.declaredMethods) {
        if (m.name != "getInstance" || m.parameterCount != 0) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed(chain.args.toTypedArray())
                    val app = result as? android.app.Application ?: return result
                    val cfg = readConfig() ?: return result
                    val snapshot = cfg.optJSONObject("lepao_snapshot")
                    // 新会话已由冷切换落盘，旧全量快照不得在启动时覆盖新身份及规则。
                    val session = cfg.opt("lepao_session")?.toString()
                        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                    val sessionUid = session?.optString("user_id")?.toLongOrNull() ?: 0L
                    if (sessionUid <= 1L && snapshot != null && snapshot.length() > 0) {
                        restoreLepaoSnapshot(app, snapshot)
                    }
                    val dumpReq = cfg.optString("lepao_dump_request", "")
                    if (dumpReq.isNotEmpty()) dumpLepaoAccount(app, dumpReq)
                    return result
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

/** 把快照 {sp:{k:{t,v}}, mmkv:{k:{s,l,i,b,d}}} 写回 SharedPreferences「setting」与 MMKV 默认实例 */
private fun restoreLepaoSnapshot(app: android.app.Application, snapshot: org.json.JSONObject) {
    var written = 0
    val sp = try {
        app.getSharedPreferences("setting", 0)
    } catch (_: Throwable) {
        null
    }
    val spSnap = snapshot.optJSONObject("sp")
    if (sp != null && spSnap != null && spSnap.length() > 0) {
        val ed = sp.edit()
        for (k in spSnap.keys()) {
            val e = spSnap.optJSONObject(k) ?: continue
            when (e.optString("t", "string")) {
                "string" -> ed.putString(k, e.optString("v", ""))
                "int" -> ed.putInt(k, e.optInt("v", 0))
                "long" -> ed.putLong(k, e.optLong("v", 0L))
                "bool" -> ed.putBoolean(k, e.optBoolean("v", false))
                "float" -> ed.putFloat(k, e.optDouble("v", 0.0).toFloat())
            }
            written++
        }
        try {
            ed.apply()
        } catch (_: Throwable) {
        }
    }
    val mmkvSnap = snapshot.optJSONObject("mmkv")
    if (mmkvSnap != null && mmkvSnap.length() > 0) {
        val mmkv = try {
            val c = Class.forName("com.tencent.mmkv.MMKV", false, app.classLoader)
            c.getMethod("mmkvWithDefault").invoke(null)
        } catch (_: Throwable) {
            null
        }
        if (mmkv != null) {
            for (k in mmkvSnap.keys()) {
                val e = mmkvSnap.optJSONObject(k) ?: continue
                try {
                    val d = e.optString("d", "").toDoubleOrNull()
                    val s = e.optString("s", "")
                    when {
                        d != null && e.has("d") && d != 0.0 ->
                            mmkv.javaClass.getMethod("encodeDouble", String::class.java, java.lang.Double.TYPE)
                                .invoke(mmkv, k, d)
                        s.isNotEmpty() ->
                            mmkv.javaClass.getMethod("encodeString", String::class.java, String::class.java)
                                .invoke(mmkv, k, s)
                        e.optInt("i", 0) != 0 ->
                            mmkv.javaClass.getMethod("encodeInt", String::class.java, java.lang.Integer.TYPE)
                                .invoke(mmkv, k, e.optInt("i", 0))
                        e.optLong("l", 0L) != 0L ->
                            mmkv.javaClass.getMethod("encodeLong", String::class.java, java.lang.Long.TYPE)
                                .invoke(mmkv, k, e.optLong("l", 0L))
                        e.optBoolean("b", false) ->
                            mmkv.javaClass.getMethod("encodeBool", String::class.java, java.lang.Boolean.TYPE)
                                .invoke(mmkv, k, true)
                        else -> continue
                    }
                    written++
                } catch (_: Throwable) {
                    // 单个 key 类型不匹配跳过，不阻断其余
                }
            }
        }
    }
    if (written > 0) {
        XposedBridge.log("[R7VqzN] lepao account snapshot restored: $written keys")
    }
}

/** 把当前账户全量 dump 到 filesDir/ls_backup_<req>.json；已存在则幂等跳过 */
private fun dumpLepaoAccount(app: android.app.Application, req: String) {
    val outFile = java.io.File(app.filesDir, "ls_backup_$req.json")
    if (outFile.exists()) return
    val dump = org.json.JSONObject()
    try {
        val sp = app.getSharedPreferences("setting", 0)
        val spJson = org.json.JSONObject()
        for ((k, v) in sp.getAll()) {
            val e = org.json.JSONObject()
            when (v) {
                is String -> { e.put("t", "string"); e.put("v", v) }
                is Int -> { e.put("t", "int"); e.put("v", v) }
                is Long -> { e.put("t", "long"); e.put("v", v) }
                is Boolean -> { e.put("t", "bool"); e.put("v", v) }
                is Float -> { e.put("t", "float"); e.put("v", v) }
                else -> { e.put("t", "string"); e.put("v", v?.toString() ?: "") }
            }
            spJson.put(k, e)
        }
        dump.put("sp", spJson)
    } catch (_: Throwable) {
    }
    val mmkv = try {
        val c = Class.forName("com.tencent.mmkv.MMKV", false, app.classLoader)
        c.getMethod("mmkvWithDefault").invoke(null)
    } catch (_: Throwable) {
        null
    }
    if (mmkv != null) {
        val mmkvJson = org.json.JSONObject()
        try {
            @Suppress("UNCHECKED_CAST")
            val keys = mmkv.javaClass.getMethod("getAllKeys").invoke(mmkv) as? Set<String>
            for (k in keys ?: emptySet<String>()) {
                val e = org.json.JSONObject()
                runCatching {
                    mmkv.javaClass.getMethod("decodeString", String::class.java, String::class.java)
                        .invoke(mmkv, k, "")?.toString()?.takeIf { it.isNotEmpty() }?.let { e.put("s", it) }
                } .getOrNull()
                runCatching {
                    mmkv.javaClass.getMethod("decodeDouble", String::class.java, java.lang.Double.TYPE)
                        .invoke(mmkv, k, 0.0) as Double?
                }.getOrNull()?.takeIf { it != 0.0 }?.let { e.put("d", it.toString()) }
                runCatching {
                    mmkv.javaClass.getMethod("decodeInt", String::class.java, java.lang.Integer.TYPE)
                        .invoke(mmkv, k, 0) as Int?
                }.getOrNull()?.takeIf { it != 0 }?.let { e.put("i", it.toString()) }
                runCatching {
                    mmkv.javaClass.getMethod("decodeLong", String::class.java, java.lang.Long.TYPE)
                        .invoke(mmkv, k, 0L) as Long?
                }.getOrNull()?.takeIf { it != 0L }?.let { e.put("l", it.toString()) }
                runCatching {
                    mmkv.javaClass.getMethod("decodeBool", String::class.java, java.lang.Boolean.TYPE)
                        .invoke(mmkv, k, false) as Boolean?
                }.getOrNull()?.takeIf { it }?.let { e.put("b", "true") }
                if (e.length() > 0) mmkvJson.put(k, e)
            }
        } catch (_: Throwable) {
        }
        dump.put("mmkv", mmkvJson)
    }
    dump.put("dumped_at", System.currentTimeMillis())
    try {
        outFile.parentFile?.mkdirs()
        outFile.writeText(dump.toString())
        XposedBridge.log("[R7VqzN] lepao account dumped: ${outFile.absolutePath} (${dump.toString().length}B)")
    } catch (t: Throwable) {
        XposedBridge.log("[R7VqzN] lepao dump write failed: ${t.message}")
    }
}

/**
 * 微信登录凭证自动捕获（wx_auto_capture）：乐跑内任何登录路径（微信扫码 / 小程序
 * 授权 / 手机验证码）成功都会走 rc.a.V0(UserDataEntity) 写登录态；在它执行完
 * 把实体字段（uid/phone/access_token/refresh_token/refresh_expire…）落盘到
 * filesDir/ls_token_capture.json，App 端 root 读回后存进 lepao_accounts +
 * lepao_session，之后不用手机再扫码，直接切户 / 协议跑。
 */
private fun hookWxTokenCapture(classLoader: ClassLoader): Int {
    val rcA = XposedHelpers.findClassIfExists("rc.a", classLoader) ?: return 0
    var hooked = 0
    for (m in rcA.declaredMethods) {
        if (m.name != "V0" || m.parameterCount != 1) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed(chain.args.toTypedArray())
                    captureLepaoLoginEntity(chain.args.getOrNull(0))
                    return result
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

private fun captureLepaoLoginEntity(entity: Any?) {
    if (entity == null) return
    try {
        val app = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
        } catch (_: Throwable) {
            null
        } ?: return
        val obj = org.json.JSONObject()
        for (f in listOf("uid", "phone", "name", "img", "role", "is_admin",
                         "access_token", "refresh_token", "refresh_expire")) {
            runCatching {
                entity.javaClass.getField(f)?.get(entity)?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { obj.put(f, it) }
            } .getOrNull()
        }
        obj.put("captured_at", System.currentTimeMillis())
        java.io.File(app.filesDir, "ls_token_capture.json").writeText(obj.toString())
        XposedBridge.log("[R7VqzN] login entity captured: uid=${obj.optString("uid")} phone=${obj.optString("phone")}")
        // 登录成功就把真实账号广播给模块：界面立刻拿到 uid/角色/token，不必等 root 读文件
        runCatching {
            val out = org.json.JSONObject(obj.toString())
            out.put("user_id", obj.optString("uid"))
            out.put("user_nickname", obj.optString("name"))
            out.put("user_avatar_url", obj.optString("img"))
            out.put("user_phone", obj.optString("phone"))
            out.put("source", "login_capture")
            app.sendBroadcast(
                android.content.Intent("com.kdzw8p.mqa.v71f.LEPAO_USER")
                    .setPackage(MODULE_PKG)
                    .putExtra("json", out.toString())
            )
        }
        broadcastWxResult("ok", "乐跑登录成功，已回写登录态（uid=${obj.optString("uid")}）", "")
    } catch (_: Throwable) {
    }
}

/** QQ 的 onComplete 执行前 PlatformDb 已存好凭据；不依赖微信换票类或字段。 */
private fun hookQqAuthCapture(classLoader: ClassLoader) {
    val helper = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.p2", classLoader) ?: return
    for (method in helper.declaredMethods) {
        if (method.name != "onComplete" || method.parameterCount != 3) continue
        try {
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val platform = chain.args.getOrNull(0)
                        if (platform != null && XposedHelpers.callMethod(platform, "getName") == "QQ") {
                            val db = XposedHelpers.callMethod(platform, "getDb")
                                ?: return chain.proceed(chain.args.toTypedArray())
                            val openid = (XposedHelpers.callMethod(db, "getUserId") as? String).orEmpty()
                            val token = (XposedHelpers.callMethod(db, "getToken") as? String).orEmpty()
                            val app = lepaoApp()
                            if (app != null && openid.isNotBlank() && token.isNotBlank()) {
                                val json = org.json.JSONObject()
                                    .put("platform", "qq")
                                    .put("openid", openid)
                                    .put("access_token", token)
                                    .put("captured_at", System.currentTimeMillis())
                                java.io.File(app.filesDir, "ls_qq_auth.json").writeText(json.toString())
                                XposedBridge.log("[R7VqzN] QQ auth captured")
                            }
                        }
                    } catch (_: Throwable) {
                        XposedBridge.log("[R7VqzN] QQ auth capture failed")
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
        } catch (_: Throwable) {
            XposedBridge.log("[R7VqzN] QQ auth capture installation failed")
        }
    }
}

/**
 * 微信 OAuth 三元组捕获（wx_auth_capture）：ShareSDK 的 WXAuthHelper
 * （cn.sharesdk.wechat.utils.h）把 code→access_token 与 refresh_token→新 access_token
 * 的响应统一解析进 PlatformDb（单个 String 参数的 a 方法）。hook 它执行完读
 * openid/token/refresh_token 落盘 filesDir/ls_wx_auth.json，App 端 root 读回后生成
 * lepao-wx:// 授权码二维码——没装微信的设备凭码走 quickLoginV339 协议直登，
 * 30 天内 refresh_token 可反复续。
 */
private fun hookWxAuthCapture(classLoader: ClassLoader): Int {
    val helper = XposedHelpers.findClassIfExists("cn.sharesdk.wechat.utils.h", classLoader) ?: return 0
    var hooked = 0
    for (m in helper.declaredMethods) {
        if (m.name != "a" || m.parameterCount != 1 || m.parameterTypes[0] != String::class.java) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed(chain.args.toTypedArray())
                    try {
                        val thisObj = chain.thisObject ?: return result
                        val platform = XposedHelpers.getObjectField(thisObj, "d") ?: return result
                        val db = XposedHelpers.callMethod(platform, "getDb") ?: return result
                        val openid = (XposedHelpers.callMethod(db, "get", "openid") as? String).orEmpty()
                        val token = (XposedHelpers.callMethod(db, "getToken") as? String).orEmpty()
                        val refresh = (XposedHelpers.callMethod(db, "get", "refresh_token") as? String).orEmpty()
                        if (openid.isBlank() || token.isBlank()) return result
                        val app = try {
                            Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication")
                                .invoke(null) as? android.app.Application
                        } catch (_: Throwable) {
                            null
                        } ?: return result
                        val obj = org.json.JSONObject()
                            .put("openid", openid)
                            .put("access_token", token)
                            .put("refresh_token", refresh)
                            .put("captured_at", System.currentTimeMillis())
                        java.io.File(app.filesDir, "ls_wx_auth.json").writeText(obj.toString())
                        XposedBridge.log("[R7VqzN] wx auth captured: openid=${openid.take(10)}… has_rt=${refresh.isNotBlank()}")
                    } catch (_: Throwable) {
                    }
                    return result
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

/**
 * 关内置广告位（force_close_ads）：乐跑自己的广告容器（WebView 底部 NativeAd、开屏 YFAd、
 * 结算页插屏）由 RunApplication 的 6 个 close*Ad 字段 + isShowAd 总开关控制。
 * 这些字段默认 false、由登录/任务奖励流程逐步打开；getInstance 里强制 close*Ad=true、
 * isShowAd=false 后，所有广告位判断恒走「不显示」分支，无需逐家 SDK 找入口。
 */
/**
 * 延迟到目标 app 的 Application.onCreate 之后执行：乐跑是爱加密/Dex2C 加固，
 * handleLoadPackage 阶段业务类（ib.f、jg.c、kwad、RunApplication）还没从壳里解出来，
 * findClassIfExists 会返回 null，导致 hook 计数为 0。Application.onCreate 时壳已完成
 * dex 解密与类加载，此时再装就能找到类。兜底再加 4 秒后强制跑一次（防 onCreate 已过）。
 */
private val deferredQueue = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

private fun deferUntilAppReady(classLoader: ClassLoader, block: () -> Unit) {
    deferredQueue.add(block)
    startDeferredDrainer(classLoader)
}

@Volatile
private var drainerStarted = false

/**
 * 统一的延迟执行器：所有需要“壳解密完成后才能找到类”的 hook 都排进队列，
 * 第一个类加载事件到达时一次性全部执行。之前每个调用各自一个 done 标志，
 * 多个线程同时触发导致只跑了第一个、其余全被跳过。
 */
@Volatile
private var drainScheduled = false

private fun startDeferredDrainer(classLoader: ClassLoader) {
    if (drainerStarted) return
    synchronized(Unit) {
        if (drainerStarted) return
        drainerStarted = true
    }

    val drain = {
        val jobs = deferredQueue.toList()
        deferredQueue.clear()
        try {
            XposedBridge.log("[R7VqzN] deferred drain start, jobs=${jobs.size}")
        } catch (_: Throwable) {
        }
        for ((i, job) in jobs.withIndex()) {
            try {
                job()
            } catch (t: Throwable) {
                XposedBridge.log("[R7VqzN] deferred job $i failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        XposedBridge.log("[R7VqzN] deferred drain done")
    }

    // 触发源：不用 ClassLoader.loadClass hook（高频核心方法，早期安装极易递归自锁，
    // 之前卡死的真因）。改为稍后（壳解密完成的典型时间点）起一个独立线程执行，
    // 并在 15 秒时再兜底一次。简单、无死锁面。
    try {
        val th = Thread({
            try {
                Thread.sleep(6000L)
            } catch (_: InterruptedException) {
            }
            if (deferredQueue.isNotEmpty()) drain()
        }, "ls-defer-6s")
        th.isDaemon = true
        th.start()
    } catch (_: Throwable) {
    }
    try {
        val th2 = Thread({
            try {
                Thread.sleep(15000L)
            } catch (_: InterruptedException) {
            }
            if (deferredQueue.isNotEmpty()) drain()
        }, "ls-defer-15s")
        th2.isDaemon = true
        th2.start()
    } catch (_: Throwable) {
    }
}

/**
 * 埋点拦截（trace_block，默认开）：乐跑的日志/埋点集中在 com.lptiyu.tanke.utils.v0 的
 * 静态方法 d(String)/e(String,String)/g(String)。这些是纯写入型 void 方法，不参与业务判断。
 * 注意：v0 也承载业务排障日志，全拦会让 logcat 失去线索——所以只拦 g（高频调试/埋点），
 * 保留 d/e 两类告警日志。类名是 com.lptiyu.tanke.utils.v0（不是简写 v0）。
 */
private fun hookTraceReporting(classLoader: ClassLoader): Int {
    var hooked = 0
    val v0 = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.v0", classLoader)
    if (v0 != null) {
        for (m in v0.declaredMethods) {
            if (m.name != "g") continue
            if (m.returnType != Void.TYPE) continue
            try {
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = null
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }
    return hooked
}

/** 广告设备指纹固定（ad_device_info，默认开）：倍孜 SDK 的 libdevInfo.so 通过
 *  com.beizi.ad.internal.utilities.DeviceInfo 取 bootMark/updateMark（设备指纹）。
 *  在 Java 层把这两个方法固定为常量，SDK 拿不到真实指纹；不影响广告展示流程本身。
 */
private fun hookAdDeviceInfo(classLoader: ClassLoader): Int {
    var hooked = 0
    val cls = XposedHelpers.findClassIfExists(
        "com.beizi.ad.internal.utilities.DeviceInfo", classLoader
    ) ?: return 0
    for (m in cls.declaredMethods) {
        if (m.name != "getBootMark" && m.name != "getUpdateMark") continue
        if (m.returnType != String::class.java) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? = "0"
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

private fun hookForceCloseAds(classLoader: ClassLoader): Int {
    var hooked = 0

    // 1) 乐跑自己的广告调度器 ib.f：d()/e() 是真实 Java 代码（非 VMP 转发），
    // e() 里做完整加载/展示调度（YFAdInterstitialAds.loadOnly / showAds）。
    // 整个方法置空即彻底不加载也不展示，比改 isShowAd 状态位可靠。
    val fCls = XposedHelpers.findClassIfExists("ib.f", classLoader)
    XposedBridge.log("[R7VqzN] ad: ib.f found=${fCls != null}")
    if (fCls != null) {
        for (m in fCls.declaredMethods) {
            if (m.name != "d" && m.name != "e") continue
            try {
                val mName = m.name
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        XposedBridge.log("[R7VqzN] ad scheduler skipped: $mName")
                        return null
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }

    // 2) 4 家广告 SDK 的展示/加载入口：不管哪条路径进来，showXxx/loadXxx 全部置空
    var sdkFound = 0
    val sdkTargets = listOf(
        "com.yfanads.android.core.inter.YFAdInterstitialAds",
        "com.beizi.fusion.NativeAd",
        "com.beizi.fusion.SplashAd",
        "com.kwad.sdk.api.KsSplashScreenAd",
        "com.kwad.sdk.api.KsInterstitialAd",
        "com.kwad.sdk.api.KsFullScreenVideoAd",
        "com.bytedance.sdk.openadsdk.TTSplashAd",
        "com.bytedance.sdk.openadsdk.TTNativeExpressAd",
        "com.bytedance.sdk.openadsdk.TTFullScreenVideoAd",
        "com.bytedance.sdk.openadsdk.TTRewardVideoAd"
    )
    for (name in sdkTargets) {
        val c = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
        sdkFound++
        for (m in c.declaredMethods) {
            val n = m.name
            val isShow = n == "showAds" || n == "show" || n.startsWith("show")
            val isLoad = n == "loadOnly" || n == "load" || n.startsWith("load")
            if (!isShow && !isLoad) continue
            try {
                val callName = "$name.$n"
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        XposedBridge.log("[R7VqzN] ad sdk call blocked: $callName")
                        return null
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }

    // 3) 原有 RunApplication 字段位（close*Ad / isShowAd），作为状态层兼容保留
    val runApp = XposedHelpers.findClassIfExists(
        "com.lptiyu.tanke.application.RunApplication", classLoader
    )
    XposedBridge.log("[R7VqzN] ad: RunApplication found=${runApp != null}, sdkClassesFound=$sdkFound, yfAd.cls=${
        XposedHelpers.findClassIfExists("com.yfanads.android.core.inter.YFAdInterstitialAds", classLoader) != null
    }")
    // 4) WebView 底部/规则广告位：setBottomAd/setRuleAd 虽是 VMP 转发，但入口在 Java 侧
    // 可 hook；置空后不创建广告容器。开屏广告由 SDK 层兜底（上面 9 个类已覆盖）
    val wv = XposedHelpers.findClassIfExists(
        "com.lptiyu.tanke.activities.WebViewActivity", classLoader
    )
    if (wv != null) {
        for (m in wv.declaredMethods) {
            if (m.name != "setBottomAd" && m.name != "setRuleAd") continue
            try {
                val mn = m.name
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        XposedBridge.log("[R7VqzN] ad slot blocked: WebViewActivity.$mn")
                        return null
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }
    if (runApp != null) {
        for (m in runApp.declaredMethods) {
            if (m.name != "getInstance" || m.parameterCount != 0) continue
            try {
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed(chain.args.toTypedArray())
                        val app = result ?: return result
                        for (f in app.javaClass.fields) {
                            if (f.type != java.lang.Boolean.TYPE) continue
                            when (f.name) {
                                "closeBottomAd", "closeShopAd", "closeCircleAd",
                                "closeGameAd", "closeRuleAd", "closeScoreAd" -> {
                                    runCatching {
                                        f.isAccessible = true
                                        f.setBoolean(app, true)
                                    }
                                }
                                "isShowAd" -> {
                                    runCatching {
                                        f.isAccessible = true
                                        f.setBoolean(app, false)
                                    }
                                }
                            }
                        }
                        return result
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }
    return hooked
}

/** 模拟器标记：cpuinfo / 传感器名称里命中任一即改写 */
private val EMULATOR_SURFACE_MARKERS = listOf("goldfish", "ranchu", "qemu", "mumu", "nemu", "ttvm", "bluestacks", "vbox")
private const val FAKE_CPU_HARDWARE = "Hardware\t: Qualcomm Technologies, Inc SM8250"
private const val FAKE_CPU_MODEL = "AArch64 Processor rev 14 (aarch64)"
private const val FAKE_WLAN_MAC = "3c:5a:b4:1f:2e:9d"

/**
 * 新 MuMu / 未伪装机型的环境里，壳与风控最常直接读的四处表面：
 * /proc/cpuinfo、/sys/class/net/<if>/address、传感器厂商与名称、开发者选项。
 * 这些值 Build 伪装盖不到，x86 MuMu 上 native 库不加载也无从拦截，只能在 Java 层换掉。
 */
private fun hookEmulatorSurface(): Int {
    var hooked = 0
    val classes = arrayOf(
        java.io.FileInputStream::class.java, java.io.FileReader::class.java,
        java.io.RandomAccessFile::class.java
    )
    for (clazz in classes) {
        for (ctor in clazz.constructors) {
            val types = ctor.parameterTypes
            val isPathCtor = types.isNotEmpty() && types[0] == String::class.java
            val isFileCtor = types.size == 1 && types[0] == java.io.File::class.java
            if (!isPathCtor && !isFileCtor) continue
            try {
                XposedHelpers.module.hook(ctor).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val raw = chain.args.getOrNull(0)
                        val path = (raw as? String) ?: (raw as? java.io.File)?.path
                        val replacement = path?.let { virtualPathFor(it) }
                        if (replacement != null) {
                            val args = chain.args.toTypedArray()
                            args[0] = if (raw is java.io.File) java.io.File(replacement) else replacement
                            return chain.proceed(args)
                        }
                        return chain.proceed(chain.args.toTypedArray())
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
    }

    // 传感器：Goldfish/ranchu 的厂商与名称是真机上不存在的组合
    try {
        val sensorClass = android.hardware.Sensor::class.java
        for (methodName in listOf("getName", "getVendor")) {
            val method = sensorClass.getMethod(methodName)
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val original = chain.proceed(chain.args.toTypedArray()) as? String ?: return null
                    val lower = original.lowercase()
                    if (EMULATOR_SURFACE_MARKERS.none { lower.contains(it) }) return original
                    return if (methodName == "getVendor") "STMicroelectronics"
                    else "LSM6DSO 3-axis Accelerometer"
                }
            })
            hooked++
        }
    } catch (_: Throwable) {
    }

    // 开发者选项：模拟器常开着 ADB，真机这个值多数是 0
    try {
        val global = Class.forName("android.provider.Settings\$Global")
        val keys = setOf("adb_enabled", "development_settings_enabled")
        for (method in global.declaredMethods.filter {
            it.name == "getInt" && it.returnType == Int::class.java
        }) {
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val key = chain.args.getOrNull(1) as? String
                    if (key != null && keys.contains(key)) return 0
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
        }
    } catch (_: Throwable) {
    }
    return hooked
}

private fun virtualPathFor(path: String): String? = when {
    path.startsWith("/proc/") && path.endsWith("/maps") -> readMapsFiltered(path)
    path == "/proc/cpuinfo" -> writeTempContent("ls_cpu_", filteredCpuinfo(path))
    path.startsWith("/sys/class/net/") && path.endsWith("/address") ->
        writeTempContent("ls_mac_", FAKE_WLAN_MAC)
    else -> null
}

/** 把 cpuinfo 里的 ranchu/goldfish 行换成高通平台真机值，其余原样保留。 */
private fun filteredCpuinfo(path: String): String? = try {
    val out = StringBuilder()
    java.io.RandomAccessFile(path, "r").use { reader ->
        var line = reader.readLine()
        while (line != null) {
            val lower = line.lowercase()
            val key = lower.substringBefore(':').trim()
            when {
                key == "hardware" -> out.append(FAKE_CPU_HARDWARE).append('\n')
                key == "model name" || key == "processor" ->
                    out.append("Processor\t: ").append(FAKE_CPU_MODEL).append('\n')
                EMULATOR_SURFACE_MARKERS.any { lower.contains(it) } -> Unit
                else -> out.append(line).append('\n')
            }
            line = reader.readLine()
        }
    }
    out.toString()
} catch (_: Throwable) {
    null
}

private fun writeTempContent(prefix: String, content: String?): String? {
    if (content == null) return null
    return try {
        val file = java.io.File.createTempFile(prefix, ".txt")
        file.writeText(content)
        file.absolutePath
    } catch (_: Throwable) {
        null
    }
}

private var kadpBlockInstalled = false
private var kadpBlockDecision: Boolean? = null

/**
 * 4.3.3 在 MuMu 上壳的 libkadp 调 art::DexFileLoader::OpenFromZipEntry 传空指针，
 * SIGSEGV 直接杀进程，Java 层无法捕获。在 System.loadLibrary/load 入口把
 * kadp / kadp_x86 / libkadp.so 的加载吞掉（返回成功不抛），把 native 崩溃换成
 * Java 层的失败分支。
 *
 * 必须在 handleLoadPackage 早期安装（壳在 StubApplication.attachBaseContext 就加载）；
 * 只对 versionCode >= 233（4.3.3）生效，4.2.3 同环境本来能启动，拦它会把好事弄坏。
 */
internal fun installKadpBlockEarly() {
    if (kadpBlockInstalled) return
    kadpBlockInstalled = true
    var hooked = 0
    for (methodName in listOf("loadLibrary", "load")) {
        try {
            val method = System::class.java.getMethod(methodName, String::class.java)
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val name = chain.args.getOrNull(0) as? String
                    if (isKadpName(name) && shouldBlockKadp()) {
                        XposedBridge.log("[R7VqzN] kadp: blocked System.$methodName($name)")
                        return null
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    XposedBridge.log("[R7VqzN] kadp: early block hooks=$hooked")
}

private fun isKadpName(name: String?): Boolean =
    !name.isNullOrBlank() && name.contains("kadp", ignoreCase = true)

/** 用系统 Context 读包版本：Application 还没创建时也能拿到，只在 4.3.3 上生效。 */
private fun shouldBlockKadp(): Boolean {
    kadpBlockDecision?.let { return it }
    val allowed = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread").invoke(null)
        val ctx = thread.javaClass.getMethod("getSystemContext").invoke(thread)
            as android.content.Context
        @Suppress("DEPRECATION")
        val code = ctx.packageManager.getPackageInfo("com.lptiyu.tanke", 0).versionCode
        code >= 233
    }.getOrDefault(false)
    kadpBlockDecision = allowed
    return allowed
}

private fun hookSystemProperties(deviceSpoofActive: Boolean): Int {
    var hooked = 0
    try {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.declaredMethods
            .filter { it.name == "get" && it.returnType == String::class.java && !Modifier.isNative(it.modifiers) }
            .forEach { method ->
                try {
                    XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val key = chain.args.getOrNull(0) as? String
                            if (key != null) {
                                basePropertyOverrides[key]?.let { return it }
                                if (deviceSpoofActive) {
                                    devicePropertyOverrides[key]?.let { return it }
                                }
                            }
                            return chain.proceed(chain.args.toTypedArray())
                        }
                    })
                    hooked++
                } catch (_: Throwable) {
                }
            }
    } catch (_: Throwable) {
    }
    return hooked
}
