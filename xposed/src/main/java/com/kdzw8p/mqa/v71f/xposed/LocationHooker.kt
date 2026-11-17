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

package com.kdzw8p.mqa.v71f.xposed

import com.kdzw8p.mqa.v71f.xposed.utils.*
import com.kdzw8p.mqa.v71f.xposed.hooks.*
import com.kdzw8p.mqa.v71f.xposed.hooks.network.*
import com.kdzw8p.mqa.v71f.xposed.hooks.vendor.VendorRegistry
import com.kdzw8p.mqa.v71f.xposed.hooks.vendor.SystemComponent
import com.kdzw8p.mqa.v71f.xposed.hooks.vendor.SystemProcess
import com.kdzw8p.mqa.v71f.xposed.diagnostics.HookStatus

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.io.File
import android.os.ParcelFileDescriptor
import com.kdzw8p.mqa.v71f.utils.FrameworkConfigChannel
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.lang.reflect.*
import java.util.concurrent.CopyOnWriteArrayList

class LocationHooker : XposedModule() {
    init {
        XposedHelpers.module = this
    }

    internal val nmeaTimers = ConcurrentHashMap<Any, java.util.Timer>()
    internal val bleScanTimers = ConcurrentHashMap<Any, java.util.Timer>()
    internal val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    // 标记本进程内是否已经安装过一次环境级 Hook（WiFi/基站/连接层/蓝牙/位置/反检测等）。
    // 这些 Hook 针对的是 ConnectivityManager/TelephonyManager/WifiManager/Location 等系统共享类，
    // 同一个类在整个进程里只有一份，不需要也不能按 classloader 重复 Hook——
    // WebView/MultiDex 等场景会让 handleLoadPackage 在同一进程内被多次调用（不同的"包名"），
    // 如果重复安装，会在同一个方法上叠加两层拦截链，libxposed 在处理带基本类型参数的方法
    // (如 ConnectivityManager.getNetworkInfo(int)) 时，叠加后的拦截链会抛出
    // IllegalArgumentException: argument N has type int, got java.lang.Integer，导致目标 App 崩溃。
    @Volatile
    internal var environmentHooksInstalled = false

    @Volatile
    internal var systemHooksInstalled = false

    @Volatile
    internal var isSystemServerProcess = false

    @Volatile
    internal var isPhoneProcessInstance = false

    @Volatile
    internal var isBluetoothProcessInstance = false

    // 用于跟踪活动的 Android LocationListener，实现动态主动欺骗
    // 使用 CopyOnWriteArrayList 和强引用，防止 GC 移除监听器
    internal val capturedLocationListeners = CopyOnWriteArrayList<Any>()
    internal val capturedAMapListeners = CopyOnWriteArrayList<Any>()
    internal val capturedBaiduListeners = CopyOnWriteArrayList<Any>()
    internal val capturedTencentListeners = CopyOnWriteArrayList<Any>()
    internal val capturedFusedLocationCallbacks = CopyOnWriteArrayList<Any>()

    @Volatile
    internal var currentPackageName: String = ""

    @Volatile
    internal var currentClassLoader: ClassLoader? = null

    @Volatile
    internal var lastSpoofedLat = 0.0

    @Volatile
    internal var lastSpoofedLng = 0.0

    @Volatile
    internal var cachedProvince = ""

    @Volatile
    internal var cachedCity = ""

    @Volatile
    internal var cachedDistrict = ""

    @Volatile
    internal var cachedStreet = ""

    @Volatile
    internal var cachedStreetNum = ""

    @Volatile
    internal var cachedAddress = ""

    @Volatile
    internal var cachedCountry = ""

    @Volatile
    internal var cachedPoiName = ""

    @Volatile
    internal var lastGeocodedLat = -999.0

    @Volatile
    internal var lastGeocodedLng = -999.0

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        startFrameworkConfigReceiver()
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 目前这里没有内容
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        val classLoader = param.classLoader
        handleLoadPackage(pkg, classLoader)
    }

    // LibXposed API 101/102: 系统服务专属入口
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        android.util.Log.e(
            "R7VqzN",
            "[SysHook] onSystemServerStarting invoked! system_server classLoader=${param.classLoader}"
        )
        handleLoadPackage("android", param.classLoader)
    }

    // LibXposed API 102: 热重载请求前置确认与资源清理
    internal val vendorExtraHooks = mutableListOf<AutoCloseable>()

    @Volatile internal var retiringGeneration = false
    private val ownedReloadTimers = java.util.Collections.synchronizedSet(mutableSetOf<java.util.Timer>())

    internal fun ownReloadTimer(timer: java.util.Timer): java.util.Timer {
        synchronized(ownedReloadTimers) {
            if (retiringGeneration) timer.cancel() else ownedReloadTimers.add(timer)
        }
        return timer
    }

    internal fun releaseReloadTimer(timer: java.util.Timer) { ownedReloadTimers.remove(timer) }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        val loader = currentClassLoader ?: return false
        if (currentPackageName.isBlank()) return false
        // A new module instance receives this bootstrap-owned state. Never pass this module or
        // module-defined registration records across the classloader boundary.
        param.setSavedInstanceState(hashMapOf<String, Any>(
            "host" to HotReloadHost(currentPackageName, loader).save(),
            "callbacks" to arrayOf(
                capturedLocationListeners.toTypedArray(), capturedAMapListeners.toTypedArray(),
                capturedBaiduListeners.toTypedArray(), capturedTencentListeners.toTypedArray(),
                capturedFusedLocationCallbacks.toTypedArray(), bleScanTimers.keys.toTypedArray()
            ),
            "system_location" to saveSystemLocationReloadState(),
            "system_ble" to saveSystemBleReloadState(),
            "sensor" to SensorStepHooker.saveReloadState()
        ))
        retiringGeneration = true
        val worker = synchronized(pollingLock) {
            val previous = configWorker
            configWorker = null
            isConfigWorkerStarted = false
            previous?.interrupt()
            previous
        }
        worker?.join(2_000)
        if (worker?.isAlive == true) {
            retiringGeneration = false
            startConfigWorker()
            return false
        }
        configReceiver?.close()
        configReceiver = null
        lastConfig = null
        synchronized(ownedReloadTimers) {
            ownedReloadTimers.forEach { it.cancel() }
            ownedReloadTimers.clear()
        }
        vendorExtraHooks.asReversed().forEach { runCatching { it.close() } }
        vendorExtraHooks.clear()
        ModuleBinderDeaths.close()
        HookStatus.close()
        nmeaTimers.values.forEach { it.cancel() }
        nmeaTimers.clear()
        bleScanTimers.values.forEach { it.cancel() }
        bleScanTimers.clear()
        clearSystemLocationReloadState()
        clearSystemBleReloadState()
        SensorStepHooker.clearReloadState()
        capturedLocationListeners.clear()
        capturedAMapListeners.clear()
        capturedBaiduListeners.clear()
        capturedTencentListeners.clear()
        capturedFusedLocationCallbacks.clear()
        // 允许重载时旧 hook 会被移除，拦截器的静态安装标记必须同步复位
        LepaoUpdateBlocker.resetForReload()
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        val state = param.savedInstanceState as? Map<*, *>
        reinstallAfterHotReload(
            state = state?.get("host"),
            // First upgrade from older versions has no saved state. Recover the already-running
            // host through its Application / system service, not module-instance fields.
            resolveHost = { resolveRunningHost(param) },
            removeOldHooks = { super.onHotReloaded(param) },
            install = { host ->
                currentPackageName = host.packageName
                currentClassLoader = host.classLoader
                HookStatus.markHotReload()
                startFrameworkConfigReceiver()
                handleLoadPackage(host.packageName, host.classLoader)
                val callbacks = state?.get("callbacks") as? Array<*>
                val lists = listOf(capturedLocationListeners, capturedAMapListeners,
                    capturedBaiduListeners, capturedTencentListeners, capturedFusedLocationCallbacks)
                lists.forEachIndexed { index, list ->
                    (callbacks?.getOrNull(index) as? Array<*>)?.filterNotNull()?.let { list.addAll(it) }
                }
                restoreSystemLocationReloadState(state?.get("system_location"))
                restoreSystemBleReloadState(state?.get("system_ble"))
                SensorStepHooker.restoreReloadState(state?.get("sensor"))
                (callbacks?.getOrNull(5) as? Array<*>)?.filterNotNull()?.forEach { callback ->
                    if (hasTypeByName(callback.javaClass, "android.bluetooth.BluetoothAdapter\$LeScanCallback")) {
                        startOldLeScanTimer(callback, host.classLoader)
                    } else startBleTimer(callback, host.classLoader)
                }
                android.util.Log.i("R7VqzN", "[HotReload] Reinstalled hooks in ${param.processName}; generation=${HookStatus.generationId}")
            }
        )
    }

    private fun resolveRunningHost(param: XposedModuleInterface.HotReloadedParam): HotReloadHost? {
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
                .invoke(null) as? android.app.Application
        }.getOrNull()
        if (!param.isSystemServer && app != null) return HotReloadHost(app.packageName, app.classLoader)
        if (param.isSystemServer) {
            // Hook handles belong to the framework; their declaring classes retain the real
            // system-service loader even when an older module did not save lifecycle state.
            param.oldHookHandles.forEach { handle ->
                val clazz = handle.executable.declaringClass
                if (clazz.name.startsWith("com.android.server.")) {
                    val loader = clazz.classLoader
                    if (loader != null) return HotReloadHost("android", loader)
                }
            }
            val serviceLoader = runCatching {
                val serviceManager = Class.forName("android.os.ServiceManager")
                serviceManager.getDeclaredMethod("getService", String::class.java).invoke(null, "location")
                    ?.javaClass?.classLoader
            }.getOrNull()
            val candidates = listOfNotNull(serviceLoader, Thread.currentThread().contextClassLoader,
                ClassLoader.getSystemClassLoader(), javaClass.classLoader?.parent)
            candidates.forEach { candidate ->
                val hostLoader = runCatching { candidate.loadClass("com.android.server.SystemServer").classLoader }.getOrNull()
                if (hostLoader != null) return HotReloadHost("android", hostLoader)
            }
        }
        return null
    }


    companion object {

        // 系统进程同样需要覆盖（android进程持有LocationManagerService）
        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone")
        internal const val VERBOSE_CELL_BUILD_LOGS = false

        fun hasTypeByName(clazz: Class<*>?, typeName: String): Boolean {
            if (clazz == null) return false
            if (clazz.name == typeName) return true
            for (iface in clazz.interfaces) {
                if (hasTypeByName(iface, typeName)) return true
            }
            return hasTypeByName(clazz.superclass, typeName)
        }
    }

    fun handleLoadPackage(pkg: String, classLoader: ClassLoader) {
        val processName = try {
            File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
        } catch (e: Exception) {
            pkg
        }
        val actualHostPkg = processName.substringBefore(":")
        if (actualHostPkg.isNotBlank() && !actualHostPkg.startsWith("android") && actualHostPkg != "system_server" && actualHostPkg != "system") {
            currentPackageName = actualHostPkg
        } else if (currentPackageName.isEmpty()) {
            currentPackageName = pkg
        }
        if (currentClassLoader == null || (pkg == currentPackageName)) {
            currentClassLoader = classLoader
        }

        startConfigWorker()
        val handledAsSystemProcess = if (BuildConfig.GLOBAL_SCHEME) {
            handleSystemProcessGlobal(pkg, processName, classLoader)
        } else {
            handleSystemProcessScoped(pkg, processName, classLoader)
        }
        if (handledAsSystemProcess) return

        // 【地图黑屏基线测试开关】对乐跑完全不注入任何 hook。
        // 默认 false（正常注入）；需要做「无模块对照」时在配置里设 "__no_hook": true。
        val lepaoNoHook = run {
            val c = readConfig()
            c?.optJSONObject("target_hook_groups")
                ?.optJSONObject(pkg)
                ?.optBoolean("__no_hook", false) ?: false
        }
        if (pkg == "com.lptiyu.tanke" && lepaoNoHook) {
            XposedBridge.log("[R7VqzN] ALL hooks skipped for $pkg (config __no_hook=true)")
            return
        }

        if (environmentHooksInstalled) {
            // 同一进程内 handleLoadPackage 被再次触发（例如宿主 App 内嵌的 WebView 会作为独立的
            // "包" 单独回调一次），但 ConnectivityManager/TelephonyManager/WifiManager/Location
            // 这些系统类在整个进程里只有一份，不需要重复 Hook，见上面 environmentHooksInstalled 的注释。
            readConfig()
            return
        }
        environmentHooksInstalled = true

        XposedBridge.log("[R7VqzN] Hooking package: $pkg")
        android.util.Log.e(
            "R7VqzN",
            "[INJECTED] Hooking package: $pkg process=$processName"
        )
        XposedBridge.logOpenCellId("handleLoadPackage pkg=$pkg classLoader=$classLoader")

        // 服务端版本门槛在 Application.onCreate 就会读 PackageInfo 并静态缓存（rc.b），
        // 等到首次 Activity.onResume（installEnvHooks）再装就晚了：请求仍带旧版本号。
        if (pkg == "com.lptiyu.tanke" || currentPackageName == "com.lptiyu.tanke") {
            LepaoUpdateBlocker.installVersionSpoofEarly(classLoader)
            // kadp 拦截实测救不了（StubApplication.attachBaseContext 就是它的 native 方法），
            // 默认不启用；保留函数供需要时打开。
            // com.kdzw8p.mqa.v71f.xposed.hooks.installKadpBlockEarly()
        }

        // 反检测 hook：会 hook AppOps / Settings.Secure / PackageManager / ContextImpl 等系统核心方法。
        // 实测（MuMu + 乐跑 4.7）：无论早期还是延时 8 秒安装，只要装上就会令乐跑
        // 在 InitializationProvider 阶段 ClassNotFoundException 崩溃。
        // 乐跑壳的黑名单里本就没有 LSPosed，这一层收益为零、风险极高 → 对乐跑默认关闭。
        val skipAntiDetect = pkg == "com.lptiyu.tanke"
        if (!skipAntiDetect) {
            hookAntiDetection(classLoader)
        } else {
            XposedBridge.log("[R7VqzN] anti-detection skipped for $pkg (shell compatibility)")
        }

        // 【地图黑屏的真正元凶】这里曾对所有包无条件调 hookLocationAPIs，
        // 而它内部会走 hookAllMapSdks → hookAMapSDK，在 handleLoadPackage 阶段
        // 就 hook 高德 SDK 的类（AMapLocation 构造函数/getter）。此时高德地图引擎
        // 还没初始化，被提前 hook 后初始化直接失败 → 地图全黑（实测确认）。
        //
        // 【关键补充】不能只判 pkg=="com.lptiyu.tanke"：乐跑的 WebView 会以
        // "com.android.webview" 为 pkg 单独回调一次，最终日志显示
        //   [INJECTED] Hooking package: com.android.webview process=com.lptiyu.tanke
        // 它是乐跑进程内的子包，仍然会把 hook 装进乐跑进程。必须按 processName
        // 判定宿主进程，否则 WebView 分支会把地图渲染环境改坏 → 依旧全黑。
        val hostIsLepao = pkg == "com.lptiyu.tanke" ||
            processName.substringBefore(":").trim() == "com.lptiyu.tanke"
        if (!hostIsLepao) {
            hookLocationAPIs(classLoader, pkg)
        } else {
            XposedBridge.log("[R7VqzN] hookLocationAPIs skipped for $pkg (host=$processName, map render protection)")
            // 乐跑自己的分享截图是 decorView 绘制（只含本应用界面，不含状态栏/导航栏/悬浮窗），
            // 所以不禁用它，保留正常分享上传；只拦 MediaProjection 全屏捕获（防未来版本改为截屏）
            try {
                XposedHelpers.findClassIfExists("android.media.projection.MediaProjectionManager", classLoader)?.let { cls ->
                    XposedHelpers.hookAllMethods(cls, "getMediaProjection") { _, _ -> null }
                    XposedBridge.log("[R7VqzN] lepao MediaProjection blocked (share screenshots untouched)")
                }
            } catch (t: Throwable) {
                XposedBridge.log("[R7VqzN] screenshot block failed: ${t.message}")
            }
        }
        hookGnssStatus(classLoader)

        // 兼容 MultiDex 与二次动态 DexClassLoader (例如百度地图 classes16.dex)
        // 【重要】不要 hook Application.attachBaseContext / onCreate！
        // 实测：乐跑是爱加密壳，Provider 在 handleBindApplication 阶段安装；
        // 任何在 Application 生命周期早期安装的 hook 都会干扰壳的类加载，
        // 导致 androidx.startup.InitializationProvider 找不到而崩溃。
        // 地图 SDK hook 已由 handleLoadPackage 主路径负责。

        // 【乐跑延迟挂载】乐跑是爱加密加固 + Dex2C，Provider 在 handleBindApplication 阶段安装。
        // 若此时装 hook 会打断壳的 classloader → InitializationProvider 找不到 → 闪退。
        // 安全模式验证了「不装 hook 就不崩」，所以这里改为：
        // 先只挂一个轻量的 Activity.onResume 探针，等首个页面真正显示（壳已完成解密）
        // 再一次性安装全部 hook。既避开崩溃窗口，又能让功能生效。
        val hookGroups = readConfig()?.optJSONObject("target_hook_groups")
            ?.optJSONObject(pkg)
        // libxposed(API 102) 的 HookBridge.invokeExactOriginalMethod 在挂满检测探针后会 JNI abort
        // （java_array / java_class / obj == null 三种，均在 ART 侧直接终止进程）。
        // 检测组默认关闭，UI 里按需单独打开；定位、版本、自动跑等核心 hook 不受影响。
        val groupEnabled: (String) -> Boolean = { name ->
            val fallback = !name.startsWith("detect_")
            hookGroups?.optBoolean(name, fallback) ?: fallback
        }

        // 非乐跑应用保持原行为（直接装）
        if (pkg != "com.lptiyu.tanke") {
            installEnvHooks(classLoader, groupEnabled)
            XposedBridge.log(
                "[R7VqzN] env hook groups for $pkg: cell=${groupEnabled("cell")} " +
                    "connectivity=${groupEnabled("connectivity")} bluetooth=${groupEnabled("bluetooth")} " +
                    "sensor=${groupEnabled("sensor_step")}"
            )
            readConfig()
            return
        }

        // 乐跑：延迟到首个 Activity.onResume 再装 hook（壳已完成解密）。
        // 可用 "__safe_mode": true 退回「完全不装 hook」的兼容模式排查问题。
        val forceSafeMode = hookGroups?.optBoolean("__safe_mode", false) ?: false
        if (forceSafeMode) {
            XposedBridge.log("[R7VqzN] SAFE MODE for $pkg: config loaded, no hooks installed")
            readConfig()
            return
        }

        armLateAttach(classLoader, groupEnabled)
        readConfig()
    }

    @Volatile private var lateAttachArmed = false
    private val lateAttachLock = Any()

    /** 挂一个 Activity.onResume 探针，首次触发时安装全部环境+检测 hook */
    private fun armLateAttach(
        classLoader: ClassLoader,
        groupEnabled: (String) -> Boolean
    ) {
        if (lateAttachArmed) return
        synchronized(lateAttachLock) {
            if (lateAttachArmed) return
            lateAttachArmed = true
        }
        try {
            XposedHelpers.hookMethod(
                "android.app.Activity",
                classLoader,
                "onResume"
            ) { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                try {
                    installEnvHooks(classLoader, groupEnabled)
                    XposedBridge.log("[R7VqzN] LATE ATTACH done (hooks installed after first onResume)")
                } catch (t: Throwable) {
                    XposedBridge.log("[R7VqzN] LATE ATTACH failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                return@hookMethod result
            }
            XposedBridge.log("[R7VqzN] late-attach armed: waiting for first Activity.onResume")
        } catch (t: Throwable) {
            XposedBridge.log("[R7VqzN] armLateAttach failed: ${t.message}")
        }
    }

    /** 实际的环境 + 检测 hook 安装体（原 handleLoadPackage 里的那一段） */
    private fun installEnvHooks(
        classLoader: ClassLoader,
        groupEnabled: (String) -> Boolean
    ) {
        // 【地图黑屏的最终定案】
        // hookLocationAPIs 内部会调 hookAllMapSdks → hookAMapSDK，hook 高德 SDK 的类
        // （AMapLocation 构造函数/getter 等），这会让高德地图引擎初始化失败 → 地图全黑。
        // 实测：只要开 hookLocationAPIs 就黑屏，关掉就正常。
        //
        // 正确方案：只 hook 乐跑自己的 t0（定位 Helper），不碰高德 SDK 任何类。
        // t0 内部把 AMapLocation 转给 BeforeRunFragment，我们改原对象字段后放行，
        // 坐标来源是 RouteEngine（按 route_points + start_timestamp 推进），能产生真实移动轨迹。
        // hookLocationAPIs(classLoader, currentPackageName.ifBlank { "com.lptiyu.tanke" })
        val addedLocationHooks = hookLepaoLocationListener(classLoader)
        XposedBridge.log("[R7VqzN] generic SDK location hooks disabled; dedicated Lepao listener hooks added=$addedLocationHooks")
        hookGnssStatus(classLoader)
        hookWifiEnvironment(classLoader)
        if (groupEnabled("cell")) hookCellEnvironment(classLoader)
        if (groupEnabled("connectivity")) hookConnectivityLayer(classLoader)
        if (groupEnabled("bluetooth")) hookBluetoothLE(classLoader)
        if (groupEnabled("sensor_step")) SensorStepHooker.hookSensorStepSimulation(classLoader)
        hookLepaoDetection(classLoader, enabled = { group -> groupEnabled(group) })
        // 服务端把当前版本判为过低后会弹强更框，点「下次再说」直接退进程（用户表现为闪退）。
        // 这里只对乐跑宿主进程装拦截，三层见 LepaoUpdateBlocker 的说明。
        if (currentPackageName == "com.lptiyu.tanke") {
            val updateHooks = LepaoUpdateBlocker.install(classLoader, readConfig())
            XposedBridge.log("[R7VqzN] lepao update blocker hooks=$updateHooks")
            // 到点自动结束：只读里程文本，到阈值后对乐跑自己的结束按钮合成一次长按
            LepaoAutoStop.install { readConfig() }
            // 权限与告警守卫：checkSelfPermission 谎报 / requestPermissions 自动授予 /
            // 必要权限、悬浮窗、GPS、作弊嫌疑这类告警弹窗在 show 前拦掉。
            // 只能放在延迟挂载里：早期安装会打崩爱加密壳的 InitializationProvider。
            installLepaoPermissionGuard(classLoader)
        }
    }

    private fun legacyInstallBlock() {
        readConfig()
    }

    /**
     * 反检测 hook 延时安装（乐跑壳兼容）：
     * hookAntiDetection 会 hook AppOps / Settings.Secure / PackageManager / ContextImpl，
     * 在 handleLoadPackage 早期执行会干扰爱加密壳的 dex 类加载。
     * 这里用独立线程延迟 8 秒安装，避开 Provider 初始化窗口。
     */
    private fun scheduleDeferredAntiDetection(classLoader: ClassLoader) {
        try {
            val t = Thread({
                try {
                    Thread.sleep(8000L)
                } catch (_: InterruptedException) {
                }
                try {
                    hookAntiDetection(classLoader)
                    XposedBridge.log("[R7VqzN] deferred anti-detection installed")
                } catch (e: Throwable) {
                    XposedBridge.log("[R7VqzN] deferred anti-detection failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }, "ls-antidetect")
            t.isDaemon = true
            t.start()
        } catch (_: Throwable) {
        }
    }

    internal fun hookAllMapSdks(cl: ClassLoader) {
        try { hookTencentSDK(cl) } catch (_: Throwable) {}
        try { hookBaiduSDK(cl) } catch (_: Throwable) {}
        try { hookGoogleFusedLocation(cl) } catch (_: Throwable) {}
    }

    /**
     * 非全局（scoped）方案下对系统进程的处理：只装基础定位与 GNSS Hook，
     * 绝对不 Hook 系统的 Wi-Fi、基站、网络状态与蓝牙底层状态机。
     * @return true 表示该进程已处理完毕（或应当跳过），调用方不再继续安装 App 内 Hook。
     */
    private fun handleSystemProcessScoped(pkg: String, processName: String, classLoader: ClassLoader): Boolean {
        // 防止注入到 SystemUI 或 com.android.bluetooth 导致崩溃或 SELinux 违规
        if (pkg == "com.android.systemui" || pkg == "com.android.bluetooth" || processName.contains("com.android.bluetooth")) {
            return true
        }

        val isSystemServer =
            (processName == "system_server") || (processName == "system")

        // 核心系统进程：system_server, com.android.phone 等
        val isCoreSystemProcess = isSystemServer ||
                processName == "com.android.phone" ||
                processName == "com.android.systemui"

        if (isCoreSystemProcess) {
            // 避免破坏系统 internal 状态机引发 NullPointerException 导致 system_server 崩溃进入安全模式
            XposedBridge.log("[R7VqzN] Core system process ($pkg / $processName) detected, skipping environment hooks to prevent system crash.")
            hookLocationAPIs(classLoader, pkg)
            hookGnssStatus(classLoader)
            readConfig()
            return true
        }
        return false
    }

    /**
     * 全局（global）方案下对系统进程的处理：在 system_server / com.android.phone / com.android.bluetooth
     * 里安装系统服务级 Hook，一次性对全设备生效。
     * @return true 表示该进程已处理完毕（或应当跳过），调用方不再继续安装 App 内 Hook。
     */
    private fun handleSystemProcessGlobal(pkg: String, processName: String, classLoader: ClassLoader): Boolean {
        // 防止注入到 SystemUI 导致崩溃或无意义占用
        if (pkg == "com.android.systemui" || processName == "com.android.systemui") {
            return true
        }

        val isSystemServer =
            (processName == "system_server") || (processName == "system")
        if (!isSystemServer && (pkg == "android" || pkg == "system")) return true
        val isPhoneProcess =
            (pkg == "com.android.phone") || (processName == "com.android.phone")
        val isBluetoothProcess =
            (pkg == "com.android.bluetooth") || (processName.startsWith("com.android.bluetooth"))

        if (isSystemServer) isSystemServerProcess = true
        if (isPhoneProcess) isPhoneProcessInstance = true
        if (isBluetoothProcess) isBluetoothProcessInstance = true

        val isCoreSystemProcess = isSystemServer || isPhoneProcess || isBluetoothProcess
        if (!isCoreSystemProcess) return false

        android.util.Log.e(
            "R7VqzN",
            "[SysHook] Core system process detected: pkg=$pkg, process=$processName, myUid=${android.os.Process.myUid()}"
        )
        if (systemHooksInstalled) {
            readConfig()
            return true
        }
        systemHooksInstalled = true
        XposedBridge.log("[SysHook] Deploying system framework hooks in $processName...")

        // 厂商适配方案的手动覆盖（VendorRegistry.applyManualOverride）必须在下面任何一个
        // hookSystemXxxService 之前生效——它们会通过 SystemClassLocator 查询 VendorRegistry，从而触发
        // VendorRegistry.active 这个 by lazy 属性首次求值，晚了就再也覆盖不了。这里提前读一次
        // 配置：readConfig() 可重复调用，后续调用只返回内存里已缓存的 lastConfig，只读内存。
        val earlyCfg = readConfig()
        VendorRegistry.applyManualOverride(earlyCfg?.optString("vendor_override", "auto"))

        // Hook 运行状态报告（App 的"系统适配 → Hook 运行状态"页读取），每个系统进程各写一份
        HookStatus.begin(
            when {
                isSystemServer -> SystemProcess.SYSTEM_SERVER
                isPhoneProcess -> SystemProcess.PHONE
                else -> SystemProcess.BLUETOOTH
            }
        )
        fun deploy(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                XposedBridge.log("[SysHook] $name error: $t")
                HookStatus.error(name, t)
            }
        }

        if (isSystemServer) {
            // 只读侦察：把各组件候选类的方法 / 字段列表打进日志，适配新系统时才需要，由配置里的调试开关控制
            if (earlyCfg?.optBoolean("debug_dump_system_services", false) == true) {
                deploy("dumpSystemServiceInternals") { dumpSystemServiceInternals(classLoader) }
            }
            deploy("hookSystemLocationService") { hookSystemLocationService(classLoader) }
            deploy("hookSystemWifiService") { hookSystemWifiService(classLoader) }
            deploy("hookSystemConnectivityService") { hookSystemConnectivityService(classLoader) }
            deploy("hookSystemTelephonyRegistry") { hookSystemTelephonyRegistry(classLoader) }
            deploy("hookSystemAppOpsService") { hookSystemAppOpsService(classLoader) }
        }

        if (isPhoneProcess) {
            deploy("hookSystemTelephonyService") { hookSystemTelephonyService(classLoader) }
        }

        if (isBluetoothProcess) {
            deploy("hookSystemBluetoothService") { hookSystemBluetoothService(classLoader) }
        }

        // 机型/系统适配层：先打印一次命中的适配器画像，再执行该机型专属的额外 Hook（默认空实现，
        // 各厂商在 vendor/profiles/ 下按需覆写）。基线 Hook 已在上面装完，这里是纯追加，不影响任何机型。
        VendorRegistry.logSelectionOnce()
        deploy("installExtraHooks") { VendorRegistry.installExtraHooks(this, classLoader) }

        val cfg = readConfig()
        android.util.Log.e(
            "R7VqzN",
            "[SysHook] System framework hooks deployed in $processName. Config active=${cfg?.optBoolean("active")}, globalMode=${cfg?.optBoolean("system_hook_global_mode")}, targetPkgs=${cfg?.optJSONArray("system_hook_packages")?.length() ?: 0}"
        )
        return true
    }

    /**
     * 只读侦察，不装任何会改变行为的 Hook：探索"直接改 system_server 内部服务实现，
     * 一次性对全设备生效"这个方向时，第一步必须先摸清楚这台设备/这个安卓版本实际的
     * 内部类结构长什么样——LocationManagerService/WifiServiceImpl 这些类不是公开 API，
     * 字段和方法名在不同安卓版本、不同 OEM 定制 ROM 之间差异很大，且没有公开文档，
     * 瞎猜字段名直接上 Hook 是 system_server 崩溃、设备进重启循环最常见的原因。
     * 这里只用反射枚举候选类的方法名/字段名打进日志，不拦截、不修改任何返回值，
     * 单个类枚举失败也只是记一条日志，不会影响 system_server 本身的运行。
     */
    internal fun dumpSystemServiceInternals(classLoader: ClassLoader) {
        // 各组件的候选类名（含当前适配器给出的定制候选），再加上两个只用于排查的 GNSS 内部类
        val candidateClassNames = SystemComponent.entries
            .filter { it.process == SystemProcess.SYSTEM_SERVER }
            .flatMap { VendorRegistry.classCandidates(it) } + listOf(
            "com.android.server.location.gnss.GnssLocationProvider",
            "com.android.server.location.gnss.GnssManagerService",
        )
        for (className in candidateClassNames) {
            try {
                val clazz = classLoader.loadClass(className)
                val methodNames = clazz.declaredMethods.map { it.name }.distinct().sorted()
                val fieldInfo = clazz.declaredFields.map { "${it.type.simpleName} ${it.name}" }.sorted()
                XposedBridge.log(
                    "[R7VqzN][SysDump] FOUND $className (superclass=${clazz.superclass?.name})\n" +
                        "  methods(${methodNames.size})=${methodNames.joinToString(",")}\n" +
                        "  fields(${fieldInfo.size})=${fieldInfo.joinToString(",")}"
                )
            } catch (e: ClassNotFoundException) {
                XposedBridge.log("[R7VqzN][SysDump] not found: $className")
            } catch (e: Throwable) {
                XposedBridge.log("[R7VqzN][SysDump] error dumping $className: $e")
            }
        }
    }

    /**
     * ★ 反检测: 隐藏Xposed环境,防止反作弊SDK检测到Hook
     *
     * 设计原则:
     * 1. 只使用精确匹配,绝不使用宽泛的contains/startsWith,避免误杀正常类
     * 2. 不Hook ClassLoader.loadClass的宽泛模式(会导致App卡死)
     * 3. 不Hook BufferedReader.readLine(开销巨大)
     * 4. 不Hook File.exists/Runtime.exec(干扰正常功能)
     */
    internal var startTimestamp = System.currentTimeMillis()

    internal val rng = Random()
    internal var hookDriftLat = 0.0
    internal var hookDriftLng = 0.0
    internal var hookAccuracyDrift = 0.0
    internal var hookLastCallTime = 0L

    /** Complete normalized snapshots are swapped atomically by the framework receiver. */
    @Volatile
    internal var lastConfig: JSONObject? = null

    @Volatile
    internal var lastOpenCellConfigLogKey: String? = null

    @Volatile
    private var configReceiver: FrameworkConfigReceiver? = null
    @Volatile private var configWorker: Thread? = null
    @Volatile private var isConfigWorkerStarted = false
    private val pollingLock = Any()

    private fun startFrameworkConfigReceiver() {
        if (configReceiver != null) return
        configReceiver = FrameworkConfigReceiver(
            getPreferences = { getRemotePreferences(FrameworkConfigChannel.GROUP) },
            readFile = { name ->
                ParcelFileDescriptor.AutoCloseInputStream(openRemoteFile(name))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            },
            onConfig = { snapshot ->
                val config = normalizeConfig(snapshot.config)
                lastConfig = config
                // 版本上报覆盖值随配置刷新生效，避免每次改动都要重启乐跑
                LepaoUpdateBlocker.onConfig(config)
                HookStatus.configLoaded(snapshot.source, snapshot.publishedAt)
                logOpenCellConfigLoaded(snapshot.source, config)
            },
            onError = { error ->
                XposedBridge.log("[Config] Framework receive failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        ).also { it.start() }
    }

    internal fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val btArray = config.optJSONArray("bluetooth_json")
        val btCount = btArray?.length() ?: 0
        val active = config.optBoolean("active", false)
        val mockBt = config.optBoolean("mock_bluetooth", true)
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val isGlobal = config.optBoolean("system_hook_global_mode", false)
        val sysPkgs = config.optJSONArray("system_hook_packages")?.length() ?: 0
        val logKey = "$active|$mockBt|$lat|$lng|$cellCount|$btCount|$isGlobal|$sysPkgs"
        if (logKey != lastOpenCellConfigLogKey) {
            lastOpenCellConfigLogKey = logKey
            val msg = "[SysHook] 配置已加载[$source]: active=$active, globalMode=$isGlobal, 目标应用数=$sysPkgs, lat=$lat, lng=$lng, 蓝牙数=$btCount, 基站数=$cellCount"
            android.util.Log.i("R7VqzN", msg)
            XposedBridge.log(msg)
        }
    }

    internal fun normalizeConfig(config: JSONObject): JSONObject {
        if (!config.has("wifi_json")) config.put("wifi_json", org.json.JSONArray())
        // UI 使用高德地图(AMapSDK)，cameraPosition.target 返回的是 GCJ-02 坐标系。
        // 因此 config["lat"/"lng"] 已经是 GCJ-02，不需要再做 BD-09 → GCJ-02 转换。
        val gcj02Lat = config.optDouble("lat", 0.0)
        val gcj02Lng = config.optDouble("lng", 0.0)

        // 派生出 BD-09（百度定位 SDK 需要）
        val bd09 = gcj02ToBd09(gcj02Lat, gcj02Lng)
        config.put("bd09_lat", bd09.first)
        config.put("bd09_lng", bd09.second)

        // 派生出 WGS-84（标准 Android Location 对象的理论坐标系）
        val wgs84 = gcj02ToWgs84(gcj02Lat, gcj02Lng)
        config.put("wgs84_lat", wgs84.first)
        config.put("wgs84_lng", wgs84.second)

        // lat/lng 保持 GCJ-02 不变，作为默认坐标（高德、腾讯等直接使用）
        return config
    }

    /** The hook call path performs no framework calls, parsing, or file IO. */
    internal fun readConfig(): JSONObject? = lastConfig

    /** Motion delivery and service discovery need a timer independently of config reception. */
    private fun startConfigWorker() {
        if (!isConfigWorkerStarted) {
            synchronized(pollingLock) {
                if (!isConfigWorkerStarted) {
                    isConfigWorkerStarted = true

                    // 后台定时器只读内存中的配置，不轮询配置文件
                    val worker = Thread {
                        val workerThread = Thread.currentThread()
                        while (configWorker === workerThread && !workerThread.isInterrupted) {
                            try {
                                Thread.sleep(1_000L)
                                val newConfig = readConfig()

                                // 若系统核心服务在初次加载时尚未初始化完成，在后台轮询线程中重试挂载，直至成功
                                // isXxxProcess 标记只在全局方案的系统进程分支里被置位，非全局方案下这三个条件恒为 false
                                if (isSystemServerProcess && !isWifiServiceHooked && currentClassLoader != null) {
                                    try { hookSystemWifiService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysWifi] Poller hookSystemWifiService error: $t") }
                                }
                                if (isPhoneProcessInstance && !isTelephonyServiceHooked && currentClassLoader != null) {
                                    try { hookSystemTelephonyService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysCell] Poller hookSystemTelephonyService error: $t") }
                                }
                                if (isBluetoothProcessInstance && !isBluetoothServiceHooked && currentClassLoader != null) {
                                    try { hookSystemBluetoothService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysBle] Poller hookSystemBluetoothService error: $t") }
                                }

                                if (newConfig != null && newConfig.optBoolean("active", false)) {
                                    val currentLat = newConfig.optDouble("lat", 0.0)
                                    val currentLng = newConfig.optDouble("lng", 0.0)
                                    lastSpoofedLat = currentLat
                                    lastSpoofedLng = currentLng

                                    // 直接从前端控制台下发的 JSON 配置中读取逆地理编码信息，0网络延迟，0硬编码
                                    cachedProvince = newConfig.optString("province", "")
                                    cachedCity = newConfig.optString("city", "")
                                    cachedDistrict = newConfig.optString("district", "")
                                    cachedStreet = newConfig.optString("street", "")
                                    cachedStreetNum = newConfig.optString("streetNum", "")
                                    cachedAddress = newConfig.optString("address", "")
                                    cachedCountry = newConfig.optString("country", "")
                                    cachedPoiName = newConfig.optString("poiName", "")

                                    val timeNow = System.currentTimeMillis()
                                    val elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                    val motion = RouteEngine.calculateCurrentPosition(newConfig, timeNow)
                                    lastSpoofedLat = motion.lat
                                    lastSpoofedLng = motion.lng

                                    val basePkg = currentPackageName.substringBefore(":")
                                    if (basePkg == "com.kdzw8p.mqa.v71f") {
                                        continue
                                    }

                                    val cl = currentClassLoader
                                    val nCount = capturedLocationListeners.size
                                    val aCount = capturedAMapListeners.size
                                    val bCount = capturedBaiduListeners.size
                                    val tCount = capturedTencentListeners.size
                                    val fCount = capturedFusedLocationCallbacks.size

                                    val isStationary = motion.speed <= 0.05
                                    // 标准 android.location.Location 按规范是 WGS-84，高德等 App 会自行转 GCJ-02；
                                    // 这里若直接给 GCJ-02 会被二次加密、偏移数百米（issue #62）
                                    val (targetLat, targetLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "WGS-84")
                                    // 漂移是带状态的 OU 过程：只调用一次，避免 lat/lng 各推进一步导致状态不同步
                                    val jittered = if (isStationary) null else getJitteredLocation(targetLat, targetLng)
                                    val pushLat = jittered?.first ?: targetLat
                                    val pushLng = jittered?.second ?: targetLng
                                    val pushSpeed = motion.speed
                                    val pushBearing = motion.bearing
                                    val pushAccuracy = if (isStationary) 2.5f else getJitteredAccuracy()
                                    val pushAltitude = RouteEngine.realisticAltitude(newConfig)

                                    val mainHandler = try {
                                        android.os.Handler(android.os.Looper.getMainLooper())
                                    } catch (_: Throwable) {
                                        null
                                    }

                                    val dispatchBlock = Runnable {
                                        if (configWorker !== workerThread) return@Runnable
                                        // 1. Android Native LocationListener & Consumer
                                        if (nCount > 0 && cl != null) {
                                            val listenersToNotify = capturedLocationListeners.toList()
                                            for (listener in listenersToNotify) {
                                                try {
                                                    val listenerCl = listener.javaClass.classLoader ?: cl
                                                    val locationClass = Class.forName(
                                                        "android.location.Location",
                                                        false,
                                                        listenerCl
                                                    )
                                                    val mockLoc =
                                                        locationClass.getConstructor(String::class.java)
                                                            .newInstance(android.location.LocationManager.GPS_PROVIDER)
                                                    XposedHelpers.callMethod(mockLoc, "setLatitude", pushLat)
                                                    XposedHelpers.callMethod(mockLoc, "setLongitude", pushLng)
                                                    XposedHelpers.callMethod(mockLoc, "setAccuracy", pushAccuracy)
                                                    XposedHelpers.callMethod(mockLoc, "setSpeed", pushSpeed)
                                                    XposedHelpers.callMethod(mockLoc, "setBearing", pushBearing)
                                                    XposedHelpers.callMethod(mockLoc, "setAltitude", pushAltitude)
                                                    XposedHelpers.callMethod(mockLoc, "setTime", timeNow)
                                                    XposedHelpers.callMethod(
                                                        mockLoc,
                                                        "setElapsedRealtimeNanos",
                                                        elapsedNanos
                                                    )
                                                    try {
                                                        val extras = android.os.Bundle().apply {
                                                            val satCount = newConfig.optInt("satellite_count", 20)
                                                            putInt("satellites", satCount)
                                                            putInt("satellites_in_view", satCount)
                                                            putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                            putInt("satellites_visible", satCount)
                                                            putBoolean("mockLocation", false)
                                                        }
                                                        XposedHelpers.callMethod(mockLoc, "setExtras", extras)
                                                    } catch (_: Throwable) {}
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockLoc,
                                                            "setIsFromMockProvider",
                                                            false
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
                                                    var called = false
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            listener,
                                                            "onLocationChanged",
                                                            mockLoc
                                                        )
                                                        called = true
                                                    } catch (_: Throwable) {}
                                                    if (!called) {
                                                        try {
                                                            XposedHelpers.callMethod(
                                                                listener,
                                                                "accept",
                                                                mockLoc
                                                            )
                                                            called = true
                                                        } catch (_: Throwable) {}
                                                    }
                                                    if (!called) {
                                                        try {
                                                            XposedHelpers.callMethod(
                                                                listener,
                                                                "onLocationChanged",
                                                                listOf(mockLoc)
                                                            )
                                                            called = true
                                                        } catch (_: Throwable) {}
                                                    }
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }

                                        // 1.5 Google FusedLocationProviderClient 的 LocationCallback
                                        if (fCount > 0 && cl != null) {
                                            val callbacksToNotify = capturedFusedLocationCallbacks.toList()
                                            for (callback in callbacksToNotify) {
                                                try {
                                                    val callbackCl = callback.javaClass.classLoader ?: cl
                                                    val locationClass = Class.forName(
                                                        "android.location.Location",
                                                        false,
                                                        callbackCl
                                                    )
                                                    val mockLoc =
                                                        locationClass.getConstructor(String::class.java)
                                                            .newInstance(android.location.LocationManager.GPS_PROVIDER)
                                                    XposedHelpers.callMethod(mockLoc, "setLatitude", pushLat)
                                                    XposedHelpers.callMethod(mockLoc, "setLongitude", pushLng)
                                                    XposedHelpers.callMethod(mockLoc, "setAccuracy", pushAccuracy)
                                                    XposedHelpers.callMethod(mockLoc, "setSpeed", pushSpeed)
                                                    XposedHelpers.callMethod(mockLoc, "setBearing", pushBearing)
                                                    XposedHelpers.callMethod(mockLoc, "setAltitude", pushAltitude)
                                                    XposedHelpers.callMethod(mockLoc, "setTime", timeNow)
                                                    XposedHelpers.callMethod(
                                                        mockLoc,
                                                        "setElapsedRealtimeNanos",
                                                        elapsedNanos
                                                    )
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockLoc,
                                                            "setIsFromMockProvider",
                                                            false
                                                        )
                                                    } catch (_: Throwable) {}

                                                    val locationResultClass = Class.forName(
                                                        "com.google.android.gms.location.LocationResult",
                                                        false,
                                                        callbackCl
                                                    )
                                                    val locationsList = java.util.Collections.singletonList(mockLoc)
                                                    val result = XposedHelpers.callStaticMethod(
                                                        locationResultClass,
                                                        "create",
                                                        locationsList
                                                    )
                                                    XposedHelpers.callMethod(callback, "onLocationResult", result)
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }

                                        // 2. AMapLocationListener
                                        if (aCount > 0 && cl != null) {
                                            val (aMapLat, aMapLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "GCJ-02")
                                            val aMapPushLat = if (isStationary) aMapLat else getJitteredLocation(aMapLat, aMapLng).first
                                            val aMapPushLng = if (isStationary) aMapLng else getJitteredLocation(aMapLat, aMapLng).second
                                            val listenersToNotify = capturedAMapListeners.toList()
                                            for (listener in listenersToNotify) {
                                                try {
                                                    val listenerCl = listener.javaClass.classLoader ?: cl
                                                    val amapLocationClass = Class.forName(
                                                        "com.amap.api.location.AMapLocation",
                                                        false,
                                                        listenerCl
                                                    )
                                                    val mockAMapLoc =
                                                        amapLocationClass.getConstructor(String::class.java)
                                                            .newInstance("gps")
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setLatitude",
                                                        aMapPushLat
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setLongitude",
                                                        aMapPushLng
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setAccuracy",
                                                        pushAccuracy
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setSpeed",
                                                        pushSpeed
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setBearing",
                                                        pushBearing
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setAltitude",
                                                        pushAltitude
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setTime",
                                                        timeNow
                                                    )
                                                    try {
                                                        XposedHelpers.callMethod(mockAMapLoc, "setSatellites", newConfig.optInt("satellite_count", 20))
                                                        XposedHelpers.callMethod(mockAMapLoc, "setGpsAccuracyStatus", 1)
                                                        XposedHelpers.callMethod(mockAMapLoc, "setLocationType", 1)
                                                    } catch (_: Throwable) {}
                                                    try {
                                                        val extras = android.os.Bundle().apply {
                                                            val satCount = newConfig.optInt("satellite_count", 20)
                                                            putInt("satellites", satCount)
                                                            putInt("satellites_in_view", satCount)
                                                            putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                            putInt("satellites_visible", satCount)
                                                            putBoolean("mockLocation", false)
                                                        }
                                                        XposedHelpers.callMethod(mockAMapLoc, "setExtras", extras)
                                                    } catch (_: Throwable) {}
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockAMapLoc,
                                                            "setElapsedRealtimeNanos",
                                                            elapsedNanos
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockAMapLoc,
                                                            "setIsFromMockProvider",
                                                            false
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
                                                    XposedHelpers.callMethod(
                                                        listener,
                                                        "onLocationChanged",
                                                        mockAMapLoc
                                                    )
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }

                                        // 3. BDLocationListener / BDAbstractLocationListener
                                        if (bCount > 0 && cl != null) {
                                            val (baiduLat, baiduLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "BD-09")
                                            val baiduPushLat = if (isStationary) baiduLat else getJitteredLocation(baiduLat, baiduLng).first
                                            val baiduPushLng = if (isStationary) baiduLng else getJitteredLocation(baiduLat, baiduLng).second
                                            val listenersToNotify = capturedBaiduListeners.toList()
                                            for (listener in listenersToNotify) {
                                                try {
                                                    val listenerCl = listener.javaClass.classLoader ?: cl
                                                    val bdLocationClass = Class.forName(
                                                        "com.baidu.location.BDLocation",
                                                        false,
                                                        listenerCl
                                                    )
                                                    val mockBDLoc =
                                                        bdLocationClass.getConstructor().newInstance()
                                                    XposedHelpers.callMethod(
                                                        mockBDLoc,
                                                        "setLatitude",
                                                        baiduPushLat
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockBDLoc,
                                                        "setLongitude",
                                                        baiduPushLng
                                                    )
                                                    try { XposedHelpers.setDoubleField(mockBDLoc, "mLatitude", baiduPushLat) } catch (_: Throwable) {}
                                                    try { XposedHelpers.setDoubleField(mockBDLoc, "mLongitude", baiduPushLng) } catch (_: Throwable) {}
                                                    try { XposedHelpers.callMethod(mockBDLoc, "setCoorType", "bd09ll") } catch (_: Throwable) {}
                                                    try { XposedHelpers.setObjectField(mockBDLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}
                                                    XposedHelpers.callMethod(mockBDLoc, "setRadius", pushAccuracy)
                                                    XposedHelpers.callMethod(mockBDLoc, "setSpeed", pushSpeed * 3.6f)
                                                    XposedHelpers.callMethod(mockBDLoc, "setDirection", pushBearing)
                                                    XposedHelpers.callMethod(mockBDLoc, "setLocType", 61)
                                                    XposedHelpers.callMethod(mockBDLoc, "setSatelliteNumber", 20)
                                                    XposedHelpers.callMethod(mockBDLoc, "setGpsCheckStatus", 1)
                                                    try { XposedHelpers.callMethod(mockBDLoc, "setMockGps", 0) } catch (_: Throwable) {}
                                                    try { XposedHelpers.callMethod(mockBDLoc, "setTime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) } catch (_: Throwable) {}
                                                    XposedHelpers.callMethod(
                                                        listener,
                                                        "onReceiveLocation",
                                                        mockBDLoc
                                                    )
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }

                                        // 4. TencentLocationListener
                                        if (tCount > 0 && cl != null) {
                                            try {
                                                val (tencentLat, tencentLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "GCJ-02")
                                                val tencentPushLat = if (isStationary) tencentLat else getJitteredLocation(tencentLat, tencentLng).first
                                                val tencentPushLng = if (isStationary) tencentLng else getJitteredLocation(tencentLat, tencentLng).second
                                                val tencentLocInterface = Class.forName(
                                                    "com.tencent.map.geolocation.TencentLocation",
                                                    false,
                                                    cl
                                                )
                                                val proxyLoc = Proxy.newProxyInstance(
                                                    cl, arrayOf(tencentLocInterface)
                                                ) { _, method, _ ->
                                                    when (method.name) {
                                                        "getLatitude" -> tencentPushLat
                                                        "getLongitude" -> tencentPushLng
                                                        "getProvider" -> "gps"
                                                        "getAccuracy" -> pushAccuracy
                                                        "getSpeed" -> pushSpeed
                                                        "getBearing" -> pushBearing
                                                        "getTime" -> timeNow
                                                        else -> null
                                                    }
                                                }
                                                val listenersToNotify = capturedTencentListeners.toList()
                                                for (listener in listenersToNotify) {
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            listener,
                                                            "onLocationChanged",
                                                            proxyLoc,
                                                            0,
                                                            "ok"
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
                                                }
                                            } catch (_: Throwable) {
                                            }
                                        }

                                        // 5. Sensor Step Simulation
                                        if (cl != null) {
                                            SensorStepHooker.dispatchStepEvents(newConfig, cl)
                                        }
                                    }

                                    if (mainHandler != null) {
                                        mainHandler.post(dispatchBlock)
                                    } else {
                                        dispatchBlock.run()
                                    }
                                }
                            } catch (_: InterruptedException) {
                                break
                            } catch (t: Throwable) {
                                XposedBridge.log("ConfigWorker error: " + t.javaClass.simpleName + ": " + t.message)
                            }
                        }
                    }.apply {
                        isDaemon = true
                        name = "R7VqzN_MotionWorker"
                    }
                    configWorker = worker
                    worker.start()

                }
            }
        }
    }

    internal var cachedGpsSatellitesList: Iterable<Any>? = null
    internal var lastGpsSatellitesUpdate = 0L
}
