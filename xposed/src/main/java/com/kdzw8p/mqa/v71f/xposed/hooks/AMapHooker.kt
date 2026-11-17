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
import com.kdzw8p.mqa.v71f.xposed.utils.*
import com.kdzw8p.mqa.v71f.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.*
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * 高德地图定位 SDK 深度拦截模块 (AMap Location SDK Hooker)
 *
 * 核心逻辑:
 * 1. AMapLocation 继承自 android.location.Location，其默认设计即为 GCJ-02 坐标系。
 * 2. 构造函数拦截：初始化即填充 GCJ-02 坐标与有效状态，防止初始 (0.0, 0.0) 泄露。
 * 3. 方法级拦截：getLatitude/getLongitude 强制返回 getCurrentSpoofedMotion("GCJ-02")。
 * 4. 回调拦截：对 AMapLocationClient.setLocationListener 注册的 AMapLocationListener 实例的
 *    onLocationChanged(AMapLocation) 进行拦截并注入最新 GCJ-02 坐标。
 * 5. 反作弊拦截：AMapLocationQualityReport 报告清零与 setMockEnable(true) 欺骗。
 */
/**
 * 强制高德只走系统 GPS（Device_Sensors）模式。
 *
 * 背景：高德 SDK 默认 Hight_Accuracy 模式，优先走自有网络定位（服务端 + WiFi/基站指纹），
 * 在 MuMu 模拟器上必然失败（假 WiFi 指纹 + 无 GPS 硬件）→ errorCode=10。
 * 而 system_server 里的
 * SystemLocationServiceHooker 已经在向乐跑主动派发伪造的 Location。
 * 把定位模式强制改成 Device_Sensors（纯 GPS），高德就会去读系统 LocationManager，
 * 从而吃到我们注入的假位置。
 */
/**
 * 修复高德地图黑屏（Android 15 / 高版本 ROM 字体渲染反射失败）。
 *
 * 日志证据：
 *   java.lang.NoSuchMethodException: android.view.accessibility.AccessibilityManager
 *     .isHighTextContrastEnabled []
 *     at com.autonavi.base.ae.gmap.glyph.ReflectUtil.invoke(ReflectUtil.java:88)
 *     at com.autonavi.base.ae.gmap.glyph.GlyphLoader.loadGlyphRaster(GlyphLoader.java:117)
 *     at com.autonavi.base.ae.gmap.GLMapEngine.nativeCreateAMapEngineWithFrame(Native Method)
 * → 字形加载器初始化失败，整个地图渲染引擎创建中止，地图全黑。
 *
 * 修复：hook 高德内部的 ReflectUtil.invoke，在反射方法不存在时返回默认值（不抛异常）。
 * 不依赖具体方法名/签名，对所有高德反射失败都兵底。
 */
internal fun LocationHooker.hookAMapGlyphReflectFix(classLoader: ClassLoader): Int {
    var hooked = 0
    val reflectCls = XposedHelpers.findClassIfExists(
        "com.autonavi.base.ae.gmap.glyph.ReflectUtil", classLoader
    ) ?: return 0
    for (m in reflectCls.declaredMethods) {
        if (m.name != "invoke") continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return try {
                        chain.proceed(chain.args.toTypedArray())
                    } catch (t: Throwable) {
                        // 反射失败（方法不存在/签名不匹配）→ 返回安全默认值，不让异常向上传播。
                        // 高德这里只会用来判断 bool 开关（isHighTextContrastEnabled 等），
                        // 返回 false 即可；非 bool 场景返回 null 也安全。
                        XposedBridge.log(
                "[R7VqzN] amap glyph reflect fallback: " +
                    "${t.javaClass.simpleName}: ${t.message}"
                        )
                        false
                    }
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    if (hooked > 0) {
        XposedBridge.log("[R7VqzN] AMap glyph reflect fix installed: $hooked")
    }
    return hooked
}

/** 根据 hook 方法的返回类型给一个安全默认值 */
private fun defaultFor(chain: XposedInterface.Chain): Any? = false

internal fun LocationHooker.hookAMapForceGpsMode(classLoader: ClassLoader): Int {
    val optCls = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationClientOption", classLoader
    ) ?: return 0
    // 枚举 AMapLocationMode：Device_Sensors / Hight_Accuracy / Battery_Saving
    // 注意：内部类名是 AMapLocationClientOption$AMapLocationMode，Kotlin 字符串里 $ 要转义
    val modeCls = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationClientOption\$AMapLocationMode", classLoader
    )
    val deviceSensors: Any? = try {
        if (modeCls != null) {
            modeCls.getField("Device_Sensors").get(null)
        } else null
    } catch (_: Throwable) {
        null
    }
    var hooked = 0
    for (m in optCls.declaredMethods) {
        if (m.name != "setLocationMode") continue
        if (m.parameterCount != 1) continue
        try {
            XposedHelpers.module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 强制改成 Device_Sensors，让高德读系统定位
                    val forced = deviceSensors
                    if (forced != null) {
                        return chain.proceed(arrayOf<Any?>(forced))
                    }
                    return chain.proceed(chain.args.toTypedArray())
                }
            })
            hooked++
            XposedBridge.log("[R7VqzN] AMapLocationClientOption.setLocationMode forced to Device_Sensors")
        } catch (_: Throwable) {
        }
    }
    return hooked
}

internal fun LocationHooker.hookAMapSDK(classLoader: ClassLoader) {
    val amapLocClass = "com.amap.api.location.AMapLocation"
    val amapLocClazz = XposedHelpers.findClassIfExists(amapLocClass, classLoader)

    if (amapLocClazz != null && hookedCallbackClasses.putIfAbsent(amapLocClazz, true) == null) {
        try {
            // 【已移除构造函数 hook】
            // AMapLocation 是 Parcelable，高德在独立进程构造后 Parcel 传给乐跑。
            // 若在构造函数里改字段，writeToParcel 写入的字节数与接收端 template 不一致：
            //   "consumed 572 bytes, but 880 expected"
            // 接收端解出的经纬度变成 3.33E-307 这类垃圾值，
            // 直接导致乐跑「定位失败：10」（errorCode=10, satellites_visible）。
            // 正确做法：只 hook getter，在读取时返回伪造值，对象本身保持完整。

            // 1. Getter 方法级拦截（唯一的伪造入口）
            XposedHelpers.hookAllMethods(amapLocClazz, "getLatitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.lat
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLongitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.lng
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getAccuracy") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = getJitteredAccuracy()
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getSpeed") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.speed
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getBearing") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.bearing
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getSatellites") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = config.optInt("satellite_count", 20)
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getGpsAccuracyStatus") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 1
                }
                return@hookAllMethods result
            }

            // 【定位失败：10 的直接修复】
            // 乐跑 LocateUserActivity.M1() 判定位成功看 getErrorCode()==0。
            // 高德在 MuMu（无 GPS 硬件）上报 errorCode=10 / satellites_visible，
            // 乐跑据此弹「定位失败：10」。这里把它伪造为 0。
            // 注意：绝对不能按 currentPackageName 跳过——本模块是全局模式注入，
            // currentPackageName 会被写成 com.kdzw8p.mqa.v71f，导致判断恒成立、
            // 伪造全被跳过（之前改三次都没生效就是因为这个）。
            XposedHelpers.hookAllMethods(amapLocClazz, "getErrorCode") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    return@hookAllMethods 0 // 0 = 定位成功
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getErrorInfo") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    return@hookAllMethods ""
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLocationDetail") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = ""
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLocationType") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 1 // 1 = GPS 定位
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLocationType") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 1 // 1: GPS 定位结果
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getAltitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = RouteEngine.realisticAltitude(config)
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getTime") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = System.currentTimeMillis()
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "isMock") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = false
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "isFixLastLocation") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = false
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getMockData") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    return@hookAllMethods null
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            hookAddressFields(amapLocClazz, classLoader)
            XposedBridge.log("[R7VqzN] AMapLocation hooks installed on ${amapLocClazz.name}")
        } catch (e: Throwable) {
            XposedBridge.log("[R7VqzN] AMapLocation hook failed: $e")
        }
    }

    // 3. Hook AMapLocationClient 注册回调与质量报告
    val clientClazz = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationClient", classLoader
    )
    if (clientClazz != null) {
        try {
            XposedHelpers.hookAllMethods(clientClazz, "setLocationListener") { chain, _ ->
                val listener = chain.args.getOrNull(0)
                if (listener != null) {
                    try {
                        val listenerClazz = listener.javaClass
                        if (hookedCallbackClasses.putIfAbsent(listenerClazz, true) == null) {
                            try {
                                XposedHelpers.hookAllMethods(listenerClazz, "onLocationChanged") { innerChain, _ ->
                                    val config = readConfig() ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    if (!config.optBoolean("active", false) || innerChain.args.isEmpty()) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val amapLoc = innerChain.args[0] ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val motion = getCurrentSpoofedMotion("GCJ-02") ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    try { XposedHelpers.callMethod(amapLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(amapLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(amapLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setAccuracy", getJitteredAccuracy()) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setSpeed", motion.speed) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setBearing", motion.bearing) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setAltitude", RouteEngine.realisticAltitude(config)) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setTime", System.currentTimeMillis()) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setSatellites", config.optInt("satellite_count", 20)) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setGpsAccuracyStatus", 1) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setLocationType", 1) } catch (_: Throwable) {}
                                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                }
                            } catch (_: Throwable) {}
                        }
                        if (LocationHooker.hasTypeByName(listener.javaClass, "com.amap.api.location.AMapLocationListener")) {
                            capturedAMapListeners.addIfAbsent(listener)
                        }
                    } catch (_: Throwable) {}
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(clientClazz, "unRegisterLocationListener") { chain, _ ->
                val listener = chain.args.getOrNull(0)
                if (listener != null) {
                    capturedAMapListeners.remove(listener)
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(clientClazz, "getLastKnownLocation") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val motion = getCurrentSpoofedMotion("GCJ-02")
                    if (motion != null) {
                        val amapLoc = result ?: try {
                            val locCls = XposedHelpers.findClass("com.amap.api.location.AMapLocation", classLoader)
                            locCls.getConstructor(String::class.java).newInstance("gps")
                        } catch (_: Throwable) { null }
                        if (amapLoc != null) {
                            try { XposedHelpers.callMethod(amapLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(amapLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(amapLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setLocationType", 1) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setGpsAccuracyStatus", 1) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setSatellites", config.optInt("satellite_count", 20)) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setRadius", getJitteredAccuracy()) } catch (_: Throwable) {}
                            return@hookAllMethods amapLoc
                        }
                    }
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(clientClazz, "setMockEnable") { chain, _ ->
                val config = readConfig()
                val args = chain.args.toTypedArray()
                if (config != null && config.optBoolean("active", false) && args.isNotEmpty()) {
                    args[0] = true
                }
                return@hookAllMethods chain.proceed(args)
            }
        } catch (_: Throwable) {}
    }

    // 4. AMapLocationQualityReport 质量报告清零
    val reportClazz = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationQualityReport", classLoader
    )
    if (reportClazz != null) {
        try {
            XposedHelpers.hookAllMethods(reportClazz, "isWifiAble") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "isInstalledHighDangerMockApp") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) false else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "getGPSSatellites") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) config.optInt("satellite_count", 20) else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "getGPSStatus") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 0 else chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}

/**
 * 接管乐跑定位回调：t0 implements AMapLocationListener。
 * 独立成函数，不放在 hookAMapSDK 的 hookedCallbackClasses 去重里——
 * 早期调用时 t0 尚未被壳解密，去重会把延迟组的重试也挡掉（实测）。
 */
// Activity恢复会多次触发延迟挂载；按实际Class去重，同时允许壳尚未加载类时稍后重试。
private val lepaoLocationListenerHooks = ConcurrentHashMap<Class<*>, Boolean>()
private val lepaoLocationResultStatusHooks = ConcurrentHashMap<Class<*>, Boolean>()

internal fun LocationHooker.hookLepaoLocationListener(classLoader: ClassLoader): Int {
        val amapLocClazz = XposedHelpers.findClassIfExists(
            "com.amap.api.location.AMapLocation", classLoader
        ) ?: return 0
        // 【定位失败：10 的根本修复】
        // 乐跑定位链路：t0 implements AMapLocationListener，高德回调 t0.onLocationChanged(AMapLocation)，
        // 再转给 t0.d.onLocationChanged()。MuMu 无 GPS 硬件，高德只上报
        // errorCode=10/satellites_visible，且 AMapLocation 跨进程 Parcel 已损坏
        // （实测 latitude=1.37E-306 这类垃圾值，consumed 336 but 636 expected）。
        // 所以直接接管 t0.onLocationChanged：不调用原方法，在乐跑进程内新建干净的
        // AMapLocation（不走 Parcel），填好伪造坐标后转给下游回调。
        try {
            val t0Cls = XposedHelpers.findClassIfExists("com.lptiyu.tanke.utils.t0", classLoader)
            if (t0Cls != null) {
                if (lepaoLocationListenerHooks.putIfAbsent(t0Cls, true) != null) return 0
                val handles = try {
                    XposedHelpers.hookAllMethods(t0Cls, "onLocationChanged") { chain, _ ->
                    val cfg = readConfig()
                    if (cfg == null || !cfg.optBoolean("active", false)) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val motion = getCurrentSpoofedMotion("GCJ-02")
                        ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    // 直接改原对象字段再放行原方法。
                    // 不新建 fake：新建对象来自我们 hook 的 classloader，乐跑实际跑的是壳
                    // 动态加载的另一份 AMapLocation，类型不匹配会导致下游收不到（之前就这样）。
                    // 用原实例自身反射 setter，类型 100% 一致。
                    val loc = chain.args.getOrNull(0)
                    if (loc != null) {
                        runCatching { XposedHelpers.callMethod(loc, "setLatitude", motion.lat) }
                        runCatching { XposedHelpers.callMethod(loc, "setLongitude", motion.lng) }
                        runCatching { XposedHelpers.callMethod(loc, "setAccuracy", getJitteredAccuracy()) }
                        runCatching { XposedHelpers.callMethod(loc, "setSpeed", motion.speed) }
                        runCatching { XposedHelpers.callMethod(loc, "setBearing", motion.bearing) }
                        runCatching { XposedHelpers.callMethod(loc, "setTime", System.currentTimeMillis()) }
                        runCatching { XposedHelpers.callMethod(loc, "setErrorCode", 0) }
                        runCatching { XposedHelpers.callMethod(loc, "setErrorInfo", "") }
                        runCatching { XposedHelpers.callMethod(loc, "setLocationType", 1) }
                        runCatching { XposedHelpers.callMethod(loc, "setProvider", "gps") }
                        runCatching {
                            XposedHelpers.callMethod(loc, "setSatellites", cfg.optInt("satellite_count", 20))
                        }
                        // 城市字段：LocateUserActivity.M1 在 errorCode==0 后会读 getCity/getCityCode
                        runCatching { XposedHelpers.callMethod(loc, "setCity", cfg.optString("city", "杭州市")) }
                        runCatching { XposedHelpers.callMethod(loc, "setCityCode", cfg.optString("city_code", "330100")) }
                        runCatching { XposedHelpers.callMethod(loc, "setProvince", cfg.optString("province", "浙江省")) }
                        runCatching { XposedHelpers.callMethod(loc, "setAdCode", cfg.optString("ad_code", "330100")) }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (t: Throwable) {
                    lepaoLocationListenerHooks.remove(t0Cls)
                    throw t
                }
                if (handles.isEmpty()) {
                    lepaoLocationListenerHooks.remove(t0Cls)
                    return 0
                }
                // 高德鉴权失败（errorCode=7）时 SDK 仍持续回调，只是每条结果都带错误码；
                // 乐跑 successLocationResult 只把 errorCode==0 的结果转给 RunHelper.n0，
                // 于是“回调不断、距离不动”，且切后台重建定位客户端才短暂恢复。
                // 模拟坐标由本模块注入，鉴权失败与位置有效性无关，这里只在模拟开启时
                // 让宿主读到成功状态；不触碰高德地图渲染类，避免历史上开 hookAMapSDK 导致的黑屏。
                val locCls = XposedHelpers.findClassIfExists("com.amap.api.location.AMapLocation", classLoader)
                if (locCls != null && lepaoLocationResultStatusHooks.putIfAbsent(locCls, true) == null) {
                    val active = { readConfig()?.optBoolean("active", false) == true }
                    XposedHelpers.hookAllMethods(locCls, "getErrorCode") { chain, _ ->
                        if (active()) 0 else chain.proceed(chain.args.toTypedArray())
                    }
                    XposedHelpers.hookAllMethods(locCls, "getErrorInfo") { chain, _ ->
                        if (active()) "" else chain.proceed(chain.args.toTypedArray())
                    }
                    XposedHelpers.hookAllMethods(locCls, "getLocationDetail") { chain, _ ->
                        if (active()) "" else chain.proceed(chain.args.toTypedArray())
                    }
                    XposedBridge.log("[R7VqzN] AMapLocation status getters hooked for host (errorCode forced to 0 while simulating)")
                }
                XposedBridge.log("[R7VqzN] t0.onLocationChanged hooked once (in-place mutation)")
                return handles.size
            }
        } catch (t: Throwable) {
            XposedBridge.log("[R7VqzN] t0 hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }


    return 0
}
