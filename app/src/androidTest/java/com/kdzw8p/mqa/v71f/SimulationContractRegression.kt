package com.kdzw8p.mqa.v71f

import android.app.Instrumentation
import android.location.GnssStatus
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.kdzw8p.mqa.v71f.data.db.AppDatabase
import com.kdzw8p.mqa.v71f.utils.FrameworkConfigChannel
import com.kdzw8p.mqa.v71f.utils.XposedModuleStatus
import com.kdzw8p.mqa.v71f.viewmodel.serializeCollectedEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** 真实设备只读检查：不发布配置、不移动坐标、不创建跑步记录，不输出设备标识或凭证。 */
internal fun Instrumentation.verifySimulationContract(): Bundle {
    val output = Bundle()
    val checks = mutableListOf<String>()
    try {
        runBlocking {
            val dao = AppDatabase.getDatabase(targetContext).environmentDao()
            val locations = dao.getAllCompleteLocations()
            val routes = dao.getCollectionRoutes()
            var wifiCount = 0
            var cellCount = 0
            var bluetoothCount = 0
            for (record in locations) {
                val jsons = serializeCollectedEnvironment(listOf(record), record.location.lat, record.location.lng)
                val wifi = JSONObject(jsons.first)
                check(wifi.getJSONArray("nearbyWifi").length() == record.wifis.map { it.device.bssid }.distinct().size)
                check(JSONArray(jsons.second).length() == record.cells.map { it.device.cellKey }.distinct().size)
                check(JSONArray(jsons.third).length() == record.bluetooths.map { it.device.address }.distinct().size)
                wifiCount += record.wifis.size
                cellCount += record.cells.size
                bluetoothCount += record.bluetooths.size
            }
            checks += "real-collected-records-serialize-without-loss"
            val empty = serializeCollectedEnvironment(emptyList(), 0.0, 0.0)
            check(JSONObject(empty.first).length() == 0 && JSONArray(empty.second).length() == 0 && JSONArray(empty.third).length() == 0)
            checks += "uncovered-location-does-not-invent-environment"
            output.putString("collections", "points=${locations.count { it.location.collectionRouteId == null }},routes=${routes.size},wifiLinks=$wifiCount,cellLinks=$cellCount,bluetoothLinks=$bluetoothCount")

            val loader = targetContext.classLoader
            val routeClass = loader.loadClass("com.kdzw8p.mqa.v71f.xposed.utils.RouteEngine")
            val routeEngine = routeClass.getField("INSTANCE").get(null)
            val calculate = routeClass.getMethod("calculateCurrentPosition", JSONObject::class.java, java.lang.Long.TYPE)
            val config = JSONObject().apply {
                put("active", true)
                put("lat", 30.0)
                put("lng", 110.0)
                put("is_route_mode", true)
                put("speed_m_s", 2.0)
                put("start_timestamp", 1000L)
                put("realism_level", 0)
                put("speed_fluctuation_pct", 0)
                put("route_points", JSONArray().put(JSONObject().put("lat", 30.0).put("lng", 110.0))
                    .put(JSONObject().put("lat", 30.01).put("lng", 110.0)))
            }
            val first = requireNotNull(calculate.invoke(routeEngine, config, 1000L))
            val next = requireNotNull(calculate.invoke(routeEngine, config, 11000L))
            fun number(obj: Any, property: String): Double = (obj.javaClass.getMethod(property).invoke(obj) as Number).toDouble()
            check(number(next, "getLat") > number(first, "getLat"))
            check(number(next, "getSpeed") > 0.0)
            config.put("is_route_mode", false).put("sim_mode", "JOYSTICK").put("speed_m_s", 0)
            val stationary = requireNotNull(calculate.invoke(routeEngine, config, 21000L))
            check(number(stationary, "getLat") == 30.0 && number(stationary, "getSpeed") == 0.0)
            checks += "route-advances-and-released-joystick-stops"

            val gnssClass = loader.loadClass("com.kdzw8p.mqa.v71f.xposed.hooks.GnssFastMockEngine")
            val engine = gnssClass.getField("INSTANCE").get(null)
            val method = gnssClass.getMethod("getOrCreateSpoofedGnssStatus", ClassLoader::class.java,
                java.lang.Integer.TYPE, java.lang.Boolean.TYPE, Any::class.java)
            val status = method.invoke(engine, loader, 10, false, null) as? GnssStatus
                ?: error("GNSS snapshot was not constructed")
            check(status.satelliteCount == 10)
            repeat(status.satelliteCount) { i ->
                check(status.getSvid(i) in 1..63)
                check(status.getConstellationType(i) in 1..7)
                check(status.getCn0DbHz(i).isFinite() && status.getCn0DbHz(i) in 0f..63f)
                check(status.usedInFix(i) && status.hasEphemerisData(i) && status.hasAlmanacData(i))
                check(status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) > 1.0e9f)
                check(status.getElevationDegrees(i) in -90f..90f && status.getAzimuthDegrees(i) in 0f..360f)
            }
            val changed = method.invoke(engine, loader, 10, true, null) as GnssStatus
            check(changed !== status) { "Jitter setting ignored by GNSS cache" }
            val resized = method.invoke(engine, loader, 12, false, null) as GnssStatus
            check(resized.satelliteCount == 12)
            checks += "android-gnss-snapshot-flags-count-and-cache"

            // 在同一 Android 实现上强制走旧版字段回退，检验实际 getter 解出的位布局。
            val holderMethod = gnssClass.getDeclaredMethod("getReflectionHolder", ClassLoader::class.java)
                .apply { isAccessible = true }
            val holder = requireNotNull(holderMethod.invoke(engine, loader))
            val builderField = holder.javaClass.getDeclaredField("builderConstructor").apply { isAccessible = true }
            val savedBuilder = builderField.get(holder)
            val fallbackFields = listOf("countField", "svidWithFlagsField", "cn0DbHzField", "elevationsField", "azimuthsField", "bitLayout")
            val missingFields = fallbackFields.filter { name ->
                holder.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(holder) == null
            }
            if (missingFields.isNotEmpty()) {
                // 普通测试进程受 hidden-API 限制，不能据此断言注入宿主的回退实现失败或通过。
                output.putString("unverified", "legacy-GNSS private-field fallback unavailable in test process: ${missingFields.joinToString()}")
            } else {
                try {
                    builderField.set(holder, null)
                    val fallback = method.invoke(engine, loader, 11, false, status) as GnssStatus
                    check(fallback === status && fallback.satelliteCount == 11) {
                        "Fallback not applied: same=${fallback === status}, count=${fallback.satelliteCount}"
                    }
                    repeat(fallback.satelliteCount) { i ->
                        check(fallback.getConstellationType(i) == GnssStatus.CONSTELLATION_BEIDOU)
                        check(fallback.getSvid(i) in 1..63)
                        check(fallback.usedInFix(i) && fallback.hasEphemerisData(i) && fallback.hasAlmanacData(i))
                        check(fallback.getCn0DbHz(i) in 30f..43.5f)
                        check(fallback.hasCarrierFrequencyHz(i) && fallback.getCarrierFrequencyHz(i) > 1.0e9f)
                    }
                    checks += "android-legacy-gnss-fallback-decodes-correctly"
                } finally {
                    builderField.set(holder, savedBuilder)
                }
            }

            val service = withTimeout(15000) {
                while (XposedModuleStatus.mService == null) delay(100)
                requireNotNull(XposedModuleStatus.mService)
            }
            val text = requireNotNull(service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null))
            val envelope = JSONObject(text)
            check(envelope.getInt("version") == FrameworkConfigChannel.VERSION)
            val file = envelope.optString("file")
            val payload = if (file.isEmpty()) envelope.getString("payload") else {
                check(FrameworkConfigChannel.isConfigFile(file))
                ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(file))
                    .bufferedReader().use { it.readText() }
            }
            val current = JSONObject(payload)
            check(current.has("active") && current.getDouble("lat").isFinite() && current.getDouble("lng").isFinite())
            output.putString("configuration", "mode=${current.optString("sim_mode")},route=${current.optBoolean("is_route_mode")},speed=${current.optDouble("speed_m_s")},routePoints=${current.optJSONArray("route_points")?.length() ?: 0},wifi=${current.optJSONObject("wifi_json")?.optJSONArray("nearbyWifi")?.length() ?: 0},cells=${current.optJSONArray("cell_json")?.length() ?: 0},bluetooth=${current.optJSONArray("bluetooth_json")?.length() ?: 0}")
            checks += "framework-snapshot-readable-and-valid"
        }
        output.putString("checks", checks.joinToString())
        output.putString("result", "PASS")
    } catch (t: Throwable) {
        val error = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        output.putString("checks", checks.joinToString())
        output.putString("result", "FAIL: ${error.javaClass.name}: ${error.message}")
    }
    return output
}
