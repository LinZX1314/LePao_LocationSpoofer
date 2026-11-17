package com.kdzw8p.mqa.v71f.xposed.hooks

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kdzw8p.mqa.v71f.xposed.utils.XposedBridge
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 乐跑强制更新拦截（弹窗文案「发现新版本 / 您当前版本过低，升级到新版本才能使用」）。
 *
 * 强更链路：服务端按客户端上报的版本号判定 → 不达标弹框 → 点「下次再说」后
 * 目标进程执行收尾动作（finish / killProcess / exit），表现为闪退。
 * 按上游到下游分三层拦：
 *
 * 1. 版本上报层（主路径，默认开启）：服务端只按请求里的 version/version_name 判定客户端版本，
 *    实测 223/4.2.3 直接返回「当前版本过低」，233/4.3.3 正常返回数据。乐跑读取自身版本的
 *    唯一来源是 PackageManager.getPackageInfo（rc.b.q()/r() 只是它的静态缓存），所以在
 *    ApplicationPackageManager 返回处改写 PackageInfo：签名是在参数表上算的，改在缓存之前，
 *    version、version_name 与 sign 天然一致。安装版本已经不低于目标值时保持真实值。
 * 2. 弹窗层：服务端判定版本过低时，在 Dialog.show 之前扫描已挂到窗口的内容，
 *    命中强更文案就不调用原 show——弹窗根本不出现，用户点不到「下次再说」，
 *    退出链路没有触发入口；构造期还没有视图时退回 show 之后 dismiss。
 * 3. 退出层（兜底）：强更弹窗出现过后的 [EXIT_GUARD_MS] 窗口内忽略收尾调用，打断闪退。
 *
 * 三层各自打日志（tag R7VqzN），logcat 可直接判定哪一层生效。
 * 只在乐跑宿主进程安装（调用方按 currentPackageName 过滤），不影响其它 App。
 */
internal object LepaoUpdateBlocker {
    /** 强更文案（对应乐跑 strings.xml 的 find_new_version / must_update_type） */
    private val FORCE_UPDATE_TEXTS = arrayOf("版本过低", "升级到新版本", "发现新版本")

    /** 进程内首次读到的真实安装版本（在改写 PackageInfo 之前抓取），供需要区分「真实安装版本」的逻辑使用 */
    @Volatile
    internal var realInstalledVersionCode = 0

    @Volatile
    internal var realInstalledVersionName = ""

    /** 服务端当前接受的最低客户端版本；实测 223/4.2.3 被拒、233/4.3.3 通过 */
    private const val TARGET_VERSION_CODE = 233
    private const val TARGET_VERSION_NAME = "4.3.3"
    private const val HOST_PKG = "com.lptiyu.tanke"

    /** 退出拦截只在强更弹窗出现后的窗口内生效，避免误伤用户主动退出 */
    private const val EXIT_GUARD_MS = 5_000L

    private val installed = AtomicBoolean(false)
    private val versionSpoofInstalled = AtomicBoolean(false)
    private val versionLogOnce = AtomicBoolean(false)
    private val packageInfoLogOnce = AtomicBoolean(false)
    private val versionCallLog = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** 壳（爱加密 StubApplication / kdp）初始化阶段也会读 PackageInfo，只改业务代码的读取结果 */
    @Volatile
    private var shellCallGuardActive = true

    @Volatile
    private var forceUpdateSeenAt = 0L

    /** 用户显式填写的覆盖值；0 / 空串 = 按真实安装版本上报（默认状态） */
    @Volatile
    private var reportVersionCode = 0

    @Volatile
    private var reportVersionName = ""

    fun install(classLoader: ClassLoader, initialConfig: org.json.JSONObject?): Int {
        if (!installed.compareAndSet(false, true)) return 0
        onConfig(initialConfig)
        val hooked = installVersionSpoofEarly(classLoader) + hookVersionReport(classLoader) +
            hookForceUpdateDialog() + hookExitPaths()
        XposedBridge.log("[R7VqzN] update: blocker installed hooks=$hooked")
        return hooked
    }

    /**
     * 框架配置刷新时同步覆盖值，只在内存里解析一次，hook 调用路径不读 JSON。
     * 空串 / 0 表示不覆盖——这是默认状态，也是唯一不会在服务端留下异常版本记录的状态。
     */
    fun onConfig(json: org.json.JSONObject?) {
        val code = json?.optString("lepao_report_version_code").orEmpty().trim().toIntOrNull() ?: 0
        val name = json?.optString("lepao_report_version_name").orEmpty().trim()
        reportVersionCode = if (code > 0) code else 0
        reportVersionName = name
        if (installed.get()) {
            val mode = if (reportVersionCode > 0 || name.isNotEmpty()) {
                "override code=$reportVersionCode name=$name"
            } else {
                "target code=$TARGET_VERSION_CODE name=$TARGET_VERSION_NAME (server minimum)"
            }
            XposedBridge.log("[R7VqzN] update: version report mode = $mode")
        }
    }

    /**
     * LSPosed 热重载会 removeOldHooks 后重走 handleLoadPackage，但本 object 的静态标记
     * 不属于新实例的 saved state；不复位就会出现「旧 hook 已被移除、新的因为 installed=true 不装」，
     * 拦截静默失效。由 onHotReloading 在确认允许重载后调用。
     */
    internal fun resetForReload() {
        installed.set(false)
        versionLogOnce.set(false)
        forceUpdateSeenAt = 0L
        reportVersionCode = 0
        reportVersionName = ""
    }

    /**
     * 早期入口：必须在 Application.onCreate 读取并缓存版本号之前安装。
     * 只装 PackageInfo 改写，其余拦截仍由 [install] 在首次 onResume 安装。
     */
    fun installVersionSpoofEarly(classLoader: ClassLoader): Int {
        if (!versionSpoofInstalled.compareAndSet(false, true)) return 0
        val hooked = hookInstalledVersion(classLoader)
        XposedBridge.log("[R7VqzN] update: early version spoof hooks=$hooked")
        return hooked
    }

    /**
     * 安装版本伪装（主路径）：ApplicationPackageManager 返回的 PackageInfo 是乐跑读取自身
     * versionCode/versionName 的唯一来源（rc.b.q()/r() 只是它的静态缓存）。在这里改写后，
     * 请求参数与 sign 都按改写值计算，不会出现「改了 version、sign 还是旧值」被服务端拒绝。
     */
    private fun hookInstalledVersion(classLoader: ClassLoader): Int {
        val cls = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", classLoader
        )
        if (cls == null) {
            XposedBridge.log("[R7VqzN] update: ApplicationPackageManager not found")
            return 0
        }
        var hooked = 0
        for (m in cls.declaredMethods) {
            if (!m.name.startsWith("getPackageInfo")) continue
            if (m.parameterTypes.isEmpty() || m.parameterTypes[0] != String::class.java) continue
            if (m.returnType != android.content.pm.PackageInfo::class.java) continue
            try {
                XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed(chain.args.toTypedArray())
                        val pkg = chain.args.getOrNull(0) as? String
                        if (pkg == HOST_PKG) rewritePackageInfo(result)
                        return result
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
        XposedBridge.log("[R7VqzN] update: installed-version hooks=$hooked")
        return hooked
    }

    /** 只在安装版本低于服务端下限时改写；用户装了 4.3.3+ 时保持真实值 */
    private fun rewritePackageInfo(info: Any?) {
        val pi = info as? android.content.pm.PackageInfo ?: return
        if (realInstalledVersionCode == 0) {
            realInstalledVersionCode = pi.versionCode
            realInstalledVersionName = pi.versionName ?: ""
        }
        val target = if (reportVersionCode > 0) reportVersionCode else TARGET_VERSION_CODE
        if (pi.versionCode >= target) return
        if (packageInfoLogOnce.compareAndSet(false, true)) {
            XposedBridge.log("[R7VqzN] update: PackageInfo ${pi.versionCode}/${pi.versionName} -> $target/$TARGET_VERSION_NAME")
        }
        // 壳只在启动初期读自己的 PackageInfo；第一次业务读取后就不再展开调用栈。
        if (shellCallGuardActive) {
            if (isShellCaller()) return
            shellCallGuardActive = false
        }
        pi.versionCode = target
        pi.versionName = if (reportVersionName.isNotEmpty()) reportVersionName else TARGET_VERSION_NAME
    }

    private fun isShellCaller(): Boolean {
        for (f in Throwable().stackTrace) {
            val n = f.className
            if (n.startsWith("com.kiwivm.") || n.startsWith("kdp.") || n == "com.lptiyu.tanke.lp") return true
        }
        return false
    }

    /**
     * rc.b.q()/r() 是 PackageInfo 的静态缓存；PackageInfo 已在源头改写，这里只保留用户
     * 显式配置的覆盖值（空值 = 不覆盖，交给 hookInstalledVersion 处理）。
     */
    private fun hookVersionReport(classLoader: ClassLoader): Int {
        val device = XposedHelpers.findClassIfExists("rc.b", classLoader)
        if (device == null) {
            XposedBridge.log("[R7VqzN] update: rc.b not found, version spoof skipped")
            return 0
        }
        var hooked = 0
        for (method in device.declaredMethods) {
            if (method.parameterCount != 0) continue
            val returnsInt = method.returnType == Int::class.javaPrimitiveType ||
                method.returnType == Int::class.javaObjectType
            val isCode = method.name == "q" && returnsInt
            val isName = method.name == "r" && method.returnType == String::class.java
            if (!isCode && !isName) continue
            val label = "rc.b.${method.name}"
            try {
                XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val real = chain.proceed(chain.args.toTypedArray())
                        val targetCode = if (reportVersionCode > 0) reportVersionCode else TARGET_VERSION_CODE
                        val targetName = if (reportVersionName.isNotEmpty()) reportVersionName else TARGET_VERSION_NAME
                        val out = if (isCode) {
                            val actual = (real as? Number)?.toInt() ?: 0
                            if (actual in 1 until targetCode) targetCode else real
                        } else {
                            val actual = real as? String
                            if (actual.isNullOrBlank() || actual == "4.2.3") targetName else real
                        }
                        val n = versionCallLog.getOrPut(label) { java.util.concurrent.atomic.AtomicInteger() }
                        if (n.getAndIncrement() < 6) {
                            XposedBridge.log("[R7VqzN] update: $label real=$real -> $out")
                        }
                        return out
                    }
                })
                hooked++
            } catch (_: Throwable) {
            }
        }
        return hooked
    }

    /**
     * 乐跑的强更框是普通 Dialog/AlertDialog。构造期 setContentView 已经把视图挂到 window 上，
     * 所以 show 之前就能读到文案：命中直接不调用原 show，用户看不到任何一帧；
     * 万一构造期还没有视图，退回 show 之后扫描并 dismiss。
     */
    private fun hookForceUpdateDialog(): Int {
        val show = try {
            Dialog::class.java.getDeclaredMethod("show")
        } catch (_: Throwable) {
            return 0
        }
        return try {
            XposedHelpers.module.hook(show).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val dialog = chain.thisObject as? Dialog
                    if (dialog != null && isForceUpdateDialog(dialog)) {
                        forceUpdateSeenAt = System.currentTimeMillis()
                        XposedBridge.log("[R7VqzN] update: force-update dialog suppressed before show")
                        return null
                    }
                    val result = chain.proceed(chain.args.toTypedArray())
                    if (dialog != null && isForceUpdateDialog(dialog)) {
                        forceUpdateSeenAt = System.currentTimeMillis()
                        XposedBridge.log("[R7VqzN] update: force-update dialog dismissed after show")
                        runCatching { dialog.dismiss() }
                    }
                    return result
                }
            })
            1
        } catch (_: Throwable) {
            0
        }
    }

    private fun isForceUpdateDialog(dialog: Dialog): Boolean {
        val texts = ArrayList<String>(8)
        runCatching { collectTexts(dialog.window?.decorView, texts) }
        return texts.any { text -> FORCE_UPDATE_TEXTS.any { text.contains(it) } }
    }

    private fun collectTexts(view: View?, out: MutableList<String>) {
        if (view == null) return
        if (view is TextView) {
            val text = view.text?.toString()
            if (!text.isNullOrBlank()) out.add(text)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out)
        }
    }

    /**
     * 「下次再说」走的是 Activity 收尾 + 进程退出两极：单拦 finish 时有些版本会再用
     * killProcess/exit 补刀，所以四个入口一起拦。
     */
    private fun hookExitPaths(): Int {
        var hooked = 0
        hooked += hookExitMethod(Activity::class.java, "finish")
        hooked += hookExitMethod(Activity::class.java, "finishAffinity")
        hooked += hookExitMethod(
            android.os.Process::class.java,
            "killProcess",
            Int::class.javaPrimitiveType!!
        )
        hooked += hookExitMethod(Runtime::class.java, "exit", Int::class.javaPrimitiveType!!)
        hooked += hookExitMethod(System::class.java, "exit", Int::class.javaPrimitiveType!!)
        return hooked
    }

    private fun hookExitMethod(
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>
    ): Int {
        return try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (inGuardWindow()) {
                        XposedBridge.log("[R7VqzN] update: ${clazz.simpleName}.$name blocked (force-update guard)")
                        return null
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            1
        } catch (_: Throwable) {
            0
        }
    }

    private fun inGuardWindow(): Boolean {
        val seen = forceUpdateSeenAt
        return seen > 0L && System.currentTimeMillis() - seen <= EXIT_GUARD_MS
    }
}
