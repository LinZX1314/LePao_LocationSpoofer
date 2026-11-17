package com.kdzw8p.mqa.v71f.utils

import android.content.Context
import android.content.SharedPreferences
import com.kdzw8p.mqa.v71f.data.BuildConfig
import com.kdzw8p.mqa.v71f.data.model.RoutePoint
import com.kdzw8p.mqa.v71f.data.model.SavedLocation
import com.kdzw8p.mqa.v71f.data.model.SavedRoute
import org.json.JSONArray
import org.json.JSONObject

class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // 默认值不再以迁移名义覆盖用户已保存的环境开关。

    var floatingScalePercent: Int
        get() = prefs.getInt("floating_scale_percent", 90).coerceIn(70, 150)
        set(value) = prefs.edit().putInt("floating_scale_percent", value.coerceIn(70, 150)).apply()

    var floatingOpacity: Float
        get() = prefs.getFloat("floating_opacity", 1f).coerceIn(0.4f, 1f)
        set(value) = prefs.edit().putFloat("floating_opacity", value.coerceIn(0.4f, 1f)).apply()

    var floatingCollapsed: Boolean
        get() = prefs.getBoolean("floating_collapsed", false)
        set(value) = prefs.edit().putBoolean("floating_collapsed", value).apply()

    /** 隐形模式：悬浮窗静止几秒后完全透明（截图/录屏里不存在），触摸立即显形；默认关，保持肉眼常显 */
    var floatingGhostMode: Boolean
        get() = prefs.getBoolean("floating_ghost_mode", false)
        set(value) = prefs.edit().putBoolean("floating_ghost_mode", value).apply()

    var floatingPreset: String
        get() = prefs.getString("floating_preset", "RUNNING") ?: "RUNNING"
        set(value) = prefs.edit().putString("floating_preset", value).apply()

    fun floatingSpeed(name: String, default: Double): Double =
        prefs.getFloat("floating_speed_$name", default.toFloat()).toDouble().coerceIn(0.1, 40.0)

    fun saveFloatingSpeed(name: String, speed: Double) {
        if (speed.isFinite()) prefs.edit().putFloat("floating_speed_$name", speed.coerceIn(0.1, 40.0).toFloat()).apply()
    }

    var routeRunMode: String
        get() = prefs.getString("route_run_mode", "MANUAL") ?: "MANUAL"
        set(value) = prefs.edit().putString("route_run_mode", value).apply()

    var routeSimMode: String
        get() = prefs.getString("route_sim_mode", "RUNNING") ?: "RUNNING"
        set(value) = prefs.edit().putString("route_sim_mode", value).apply()

    var routeCustomSpeedMs: Double
        get() = prefs.getFloat("route_custom_speed", 3f).toDouble()
        set(value) { if (value.isFinite()) prefs.edit().putFloat("route_custom_speed", value.coerceIn(0.1, 100.0).toFloat()).apply() }

    var routeStepCadence: Int
        get() = prefs.getInt("route_step_cadence", 90).coerceIn(60, 240)
        set(value) = prefs.edit().putInt("route_step_cadence", value.coerceIn(60, 240)).apply()

    var routeUseReal: Boolean
        get() = prefs.getBoolean("route_use_real", false)
        set(value) = prefs.edit().putBoolean("route_use_real", value).apply()

    var routeStopAtDestination: Boolean
        get() = prefs.getBoolean("route_stop_at_destination", false)
        set(value) = prefs.edit().putBoolean("route_stop_at_destination", value).apply()

    var routeEnableSteps: Boolean
        get() = prefs.getBoolean("route_enable_steps", true)
        set(value) = prefs.edit().putBoolean("route_enable_steps", value).apply()

    var routeAutoCadence: Boolean
        get() = prefs.getBoolean("route_auto_cadence", false)
        set(value) = prefs.edit().putBoolean("route_auto_cadence", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", true)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value).apply()

    var isLanguageSet: Boolean
        get() = prefs.getBoolean("is_language_set", false)
        set(value) = prefs.edit().putBoolean("is_language_set", value).apply()

    var amapApiKey: String
        get() = prefs.getString("amap_api_key", "") ?: ""
        set(value) = prefs.edit().putString("amap_api_key", value).apply()

    var baiduApiKey: String
        get() = prefs.getString("baidu_api_key", "") ?: ""
        set(value) = prefs.edit().putString("baidu_api_key", value).apply()

    var googleApiKey: String
        get() = prefs.getString("google_api_key", "") ?: ""
        set(value) = prefs.edit().putString("google_api_key", value).apply()

    var wigleApiToken: String
        get() = prefs.getString("wigle_api_token", "") ?: ""
        set(value) = prefs.edit().putString("wigle_api_token", value).apply()

    var opencellidApiToken: String
        get() = prefs.getString("opencellid_api_token", "") ?: ""
        set(value) = prefs.edit().putString("opencellid_api_token", value).apply()

    var mapType: String
        get() = prefs.getString("map_type", "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit().putString("map_type", value).apply()

    var mapEngine: String
        get() = prefs.getString("map_engine", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("map_engine", value).apply()

    var rootSolution: String
        get() = prefs.getString("root_solution", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("root_solution", value).apply()

    /** 用户在"厂商适配方案"设置页手动选中的 [com.kdzw8p.mqa.v71f.vendor.VendorScheme.id]；"auto" 表示走自动识别。 */
    var vendorOverride: String
        get() = prefs.getString("vendor_override", "auto") ?: "auto"
        set(value) = prefs.edit().putString("vendor_override", value).apply()

    /** 适配新系统用：开机时把各系统服务候选类的方法 / 字段列表写进 Xposed 日志（全局方案，需重启生效） */
    var debugDumpSystemServices: Boolean
        get() = prefs.getBoolean("debug_dump_system_services", false)
        set(value) = prefs.edit().putBoolean("debug_dump_system_services", value).apply()

    var ignoredVersion: String
        get() = prefs.getString("ignored_version", "") ?: ""
        set(value) = prefs.edit().putString("ignored_version", value).apply()

    var checkBetaUpdates: Boolean
        get() = prefs.getBoolean("check_beta_updates", false)
        set(value) = prefs.edit().putBoolean("check_beta_updates", value).apply()

    /** 全局界面缩放（百分比），100 = 系统原生大小；控制 App 内所有 Compose 布局的 Density */
    var uiScalePercent: Int
        get() = prefs.getInt("ui_scale_percent", 100)
        set(value) = prefs.edit().putInt("ui_scale_percent", value).apply()

    var isSpoofingActive: Boolean
        get() = prefs.getBoolean("is_spoofing_active", false)
        set(value) = prefs.edit().putBoolean("is_spoofing_active", value).apply()

    var lastSpoofedLat: String
        get() = prefs.getString("last_spoofed_lat", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lat", value).apply()

    var lastSpoofedLng: String
        get() = prefs.getString("last_spoofed_lng", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lng", value).apply()

    var mockWifi: Boolean
        get() = prefs.getBoolean("mock_wifi", false)
        set(value) = prefs.edit().putBoolean("mock_wifi", value).apply()

    var mockCell: Boolean
        get() = prefs.getBoolean("mock_cell", true)
        set(value) = prefs.edit().putBoolean("mock_cell", value).apply()

    var mockBluetooth: Boolean
        get() = prefs.getBoolean("mock_bluetooth", true)
        set(value) = prefs.edit().putBoolean("mock_bluetooth", value).apply()

    var enableJitter: Boolean
        get() = prefs.getBoolean("enable_jitter", true)
        set(value) = prefs.edit().putBoolean("enable_jitter", value).apply()

    /** 开始模拟时是否强制重启已勾选作用域的目标 App，让它们加载当前模块并订阅框架配置 */
    var restartAppsOnSpoof: Boolean
        get() = prefs.getBoolean("restart_apps_on_spoof", true)
        set(value) = prefs.edit().putBoolean("restart_apps_on_spoof", value).apply()

    var altitude: String
        get() = prefs.getString("altitude", "0.0") ?: "0.0"
        set(value) = prefs.edit().putString("altitude", value).apply()

    /** 海拔相对基准值的最大起伏（±米），0 表示固定海拔 */
    var altitudeVariationM: Int
        get() = prefs.getInt("altitude_variation_m", AltitudeModel.DEFAULT_VARIATION_M)
        set(value) = prefs.edit().putInt("altitude_variation_m", value).apply()

    /** 运动真实度随机强度，取值见 MotionRealism.Level.id */
    var realismLevel: Int
        get() = prefs.getInt("realism_level", MotionRealism.DEFAULT_LEVEL_ID)
        set(value) = prefs.edit().putInt("realism_level", value).apply()

    /** 路径横向抖动幅度百分比：0 = 完全贴线，100 = 现有默认摆动，越大越“人味” */
    var realismSwayScalePct: Int
        get() = prefs.getInt("realism_sway_scale_pct", MotionRealism.DEFAULT_SWAY_SCALE_PCT)
        set(value) = prefs.edit().putInt("realism_sway_scale_pct", value).apply()

    /** 路径横向随机偏移幅度（米）：沿里程缓变，直线段也会左右摆；0 = 完全贴线 */
    var realismRouteOffsetM: Int
        get() = prefs.getInt("realism_route_offset_m", MotionRealism.DEFAULT_ROUTE_OFFSET_M)
            .coerceIn(0, MotionRealism.MAX_ROUTE_OFFSET_ABS_M)
        set(value) = prefs.edit().putInt("realism_route_offset_m", value).apply()

    /** 路线模拟速度浮动范围（±百分比） */
    var speedFluctuationPct: Int
        get() = prefs.getInt("speed_fluctuation_pct", MotionRealism.DEFAULT_SPEED_FLUCTUATION_PCT)
        set(value) = prefs.edit().putInt("speed_fluctuation_pct", value).apply()

    // 片段式降速：每段比基准配速压低多少、单段最短 / 最长持续、两段之间恢复常态的平均间隔
    var slowdownPct: Int
        get() = prefs.getInt("slowdown_pct", MotionRealism.DEFAULT_SLOWDOWN_PCT)
        set(value) = prefs.edit().putInt("slowdown_pct", value).apply()

    var slowdownMinSec: Int
        get() = prefs.getInt("slowdown_min_sec", MotionRealism.DEFAULT_SLOWDOWN_MIN_SEC)
        set(value) = prefs.edit().putInt("slowdown_min_sec", value).apply()

    var slowdownMaxSec: Int
        get() = prefs.getInt("slowdown_max_sec", MotionRealism.DEFAULT_SLOWDOWN_MAX_SEC)
        set(value) = prefs.edit().putInt("slowdown_max_sec", value).apply()

    var slowdownGapSec: Int
        get() = prefs.getInt("slowdown_gap_sec", MotionRealism.DEFAULT_SLOWDOWN_GAP_SEC)
        set(value) = prefs.edit().putInt("slowdown_gap_sec", value).apply()

    /** 缓入缓出时长（秒），0 = 按每段时长自动推导 */
    var slowdownRampSec: Int
        get() = prefs.getInt("slowdown_ramp_sec", MotionRealism.DEFAULT_SLOWDOWN_RAMP_SEC)
        set(value) = prefs.edit().putInt("slowdown_ramp_sec", value).apply()

    /** 降速时步频跟随下降的百分比 */
    var cadenceDipPct: Int
        get() = prefs.getInt("cadence_dip_pct", MotionRealism.DEFAULT_CADENCE_DIP_PCT)
        set(value) = prefs.edit().putInt("cadence_dip_pct", value).apply()

    /** 录制的个人步态模板（GaitTemplate.encode() 的结果），空串表示未录制 */
    var gaitTemplate: String
        get() = prefs.getString("gait_template", "") ?: ""
        set(value) = prefs.edit().putString("gait_template", value).apply()

    var useGaitTemplate: Boolean
        get() = prefs.getBoolean("use_gait_template", false)
        set(value) = prefs.edit().putBoolean("use_gait_template", value).apply()

    /** 打开 App 时地图停留在上次选定的位置，而不是自动跳到真实位置 */
    var keepLastMapPosition: Boolean
        get() = prefs.getBoolean("keep_last_map_position", true)
        set(value) = prefs.edit().putBoolean("keep_last_map_position", value).apply()

    var lastMapLat: String
        get() = prefs.getString("last_map_lat", "") ?: ""
        set(value) = prefs.edit().putString("last_map_lat", value).apply()

    var lastMapLng: String
        get() = prefs.getString("last_map_lng", "") ?: ""
        set(value) = prefs.edit().putString("last_map_lng", value).apply()

    var satelliteCount: String
        get() = prefs.getString("satellite_count", "10") ?: "10"
        set(value) = prefs.edit().putString("satellite_count", value).apply()

    fun getSavedLocations(): List<SavedLocation> {
        val jsonString = prefs.getString("saved_locations", "[]") ?: "[]"
        val list = mutableListOf<SavedLocation>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SavedLocation(
                        name = obj.optString("name", ""),
                        lat = obj.optDouble("lat", 0.0),
                        lng = obj.optDouble("lng", 0.0),
                        wifiJson = obj.optString("wifiJson", "[]"),
                        cellJson = obj.optString("cellJson", "[]"),
                        bluetoothJson = obj.optString("bluetoothJson", "[]"),
                        // 老版本写入的收藏没有这个 key，has() 为 false 时保持 null
                        sourceLocationId = if (obj.has("sourceLocationId")) obj.optLong("sourceLocationId") else null
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * 两条收藏是否视为"同一条"：都来自同一条采集记录（sourceLocationId 相同且非空）时按
     * 来源关联判断——这样编辑过采集点坐标也认得出来；否则退回按 name+lat+lng 精确匹配
     * （手动收藏、或老版本写入的没有来源 id 的收藏）。不能只按坐标匹配，那样会把同一坐标
     * 下其他名字/其他来源的收藏一并命中。
     */
    private fun isSameSavedLocation(a: SavedLocation, b: SavedLocation): Boolean {
        if (a.sourceLocationId != null && b.sourceLocationId != null) {
            return a.sourceLocationId == b.sourceLocationId
        }
        return a.name == b.name && a.lat == b.lat && a.lng == b.lng
    }

    fun addSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.removeAll { isSameSavedLocation(it, location) }
        list.add(location)
        saveLocationList(list)
    }

    fun removeSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.removeAll { isSameSavedLocation(it, location) }
        saveLocationList(list)
    }

    fun saveLocationList(list: List<SavedLocation>) {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("lat", it.lat)
            obj.put("lng", it.lng)
            obj.put("wifiJson", it.wifiJson)
            obj.put("cellJson", it.cellJson)
            obj.put("bluetoothJson", it.bluetoothJson)
            if (it.sourceLocationId != null) {
                obj.put("sourceLocationId", it.sourceLocationId)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_locations", jsonArray.toString()).apply()
    }

    fun getSavedRoutes(): List<SavedRoute> {
        val jsonString = prefs.getString("saved_routes", "[]") ?: "[]"
        val list = mutableListOf<SavedRoute>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pointsArray = obj.getJSONArray("points")
                val points = (0 until pointsArray.length()).map { j ->
                    val p = pointsArray.getJSONObject(j)
                    RoutePoint(p.getDouble("lat"), p.getDouble("lng"))
                }
                list.add(SavedRoute(obj.getString("name"), points))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.add(route)
        saveRouteList(list)
    }

    fun removeSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.removeAll { it.name == route.name }
        saveRouteList(list)
    }

    private fun saveRouteList(list: List<SavedRoute>) {
        val jsonArray = JSONArray()
        list.forEach { route ->
            val obj = JSONObject()
            obj.put("name", route.name)
            val pointsArray = JSONArray()
            route.points.forEach { p ->
                val pObj = JSONObject()
                pObj.put("lat", p.lat)
                pObj.put("lng", p.lng)
                pointsArray.put(pObj)
            }
            obj.put("points", pointsArray)
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_routes", jsonArray.toString()).apply()
    }

    fun getAppCoordinateSystems(): Map<String, String> {
        val jsonString = prefs.getString("app_coordinate_systems", "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val jsonObj = JSONObject(jsonString)
            for (key in jsonObj.keys()) {
                map[key] = jsonObj.getString(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun setAppCoordinateSystems(map: Map<String, String>) {
        val jsonObj = JSONObject()
        map.forEach { (k, v) -> jsonObj.put(k, v) }
        prefs.edit().putString("app_coordinate_systems", jsonObj.toString()).apply()
    }

    /** system_server 级定位 Hook（实验性）里被勾选生效的目标 App 包名集合 */
    fun getSystemHookPackages(): Set<String> {
        val jsonString = prefs.getString("system_hook_packages", "[]") ?: "[]"
        val set = mutableSetOf<String>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                set.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return set
    }

    fun setSystemHookPackages(packages: Set<String>) {
        val jsonArray = JSONArray()
        packages.forEach { jsonArray.put(it) }
        prefs.edit().putString("system_hook_packages", jsonArray.toString()).apply()
    }

    /** 是否开启全局模拟模式（除本应用与系统基础核心外对所有应用生效） */
    var isSystemHookGlobalMode: Boolean
        get() = prefs.getBoolean("is_system_hook_global_mode", true)
        set(value) = prefs.edit().putBoolean("is_system_hook_global_mode", value).apply()

    /**
     * 按目标应用裁剪环境 hook 组的持久化内容，形如 {"com.lptiyu.tanke":{"cell":false,...}}。
     * 只存显式配置过的应用；未出现的应用由 Xposed 端按全开处理。
     */
    fun getTargetHookGroupsJson(): JSONObject {
        val raw = prefs.getString("target_hook_groups", "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun setTargetHookGroupsJson(json: JSONObject) {
        prefs.edit().putString("target_hook_groups", json.toString()).apply()
    }

    /** 读取某个包名的组开关；没配置过时视为全开 */
    fun isHookGroupEnabled(pkg: String, group: String): Boolean =
        getTargetHookGroupsJson().optJSONObject(pkg)?.optBoolean(group, true) ?: true

    fun setHookGroupEnabled(pkg: String, group: String, enabled: Boolean) {
        val root = getTargetHookGroupsJson()
        val app = root.optJSONObject(pkg) ?: JSONObject().also { root.put(pkg, it) }
        app.put(group, enabled)
        setTargetHookGroupsJson(root)
    }

    var forceLocationEnabled: Boolean
        get() = prefs.getBoolean("force_location_enabled", false)
        set(value) = prefs.edit().putBoolean("force_location_enabled", value).apply()

    /**
     * 乐跑多用户配置（profiles）：每个用户一份 {lat,lng,sim_mode,groups}，
     * 切换时写回当前设置并重发一次配置。未创建过任何用户时为空对象。
     */
    fun getLepaoProfiles(): JSONObject {
        val raw = prefs.getString("lepao_profiles", "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun setLepaoProfiles(json: JSONObject) {
        prefs.edit().putString("lepao_profiles", json.toString()).apply()
    }

    fun saveLepaoProfile(name: String, profile: JSONObject) {
        val root = getLepaoProfiles()
        fun credentials(value: JSONObject?): Pair<String, String> {
            val session = value?.optString("session")?.let { runCatching { JSONObject(it) }.getOrNull() }
            return session?.optString("user_id").orEmpty() to
                session?.optString("token")?.ifBlank { session.optString("access_token") }.orEmpty()
        }
        val existing = root.optJSONObject(name)
        val retired = existing?.optJSONArray("retired_session_hashes")
        val incomingToken = credentials(profile).second
        if (retired != null && incomingToken.isNotBlank() && (0 until retired.length()).any {
                retired.optString(it) == LepaoApiCrypto.md5(incomingToken)
            }) {
            // 新票已提交后，迟到的宿主导出只能更新资料，不能恢复已作废的旧票。
            profile.put("session", existing.optString("session"))
            profile.put("retired_session_hashes", retired)
        }
        // 查询状态属于会话，不属于昵称；换票后旧的失效判定与成功时间都要重新校验。
        if (credentials(existing) != credentials(profile)) {
            listOf("stats_error", "rule_error", "stats_fetched_at", "rule_captured_at",
                "stats_attempt_at", "rule_attempt_at", "today_run_day", "today_run_count",
                "today_run_error", "today_run_fetched_at").forEach { profile.remove(it) }
        }
        root.put(name, profile)
        setLepaoProfiles(root)
    }

    /** 服务端确认UID之后一次提交合并结果，旧占位和重复UID不再逐条落库。 */
    @Synchronized
    fun mergeLepaoSocialProfile(provider: String, openId: String, authorized: JSONObject): String {
        val merged = LepaoSocialProfiles.merge(getLepaoProfiles(), provider, openId, authorized)
        setLepaoProfiles(merged.profiles)
        return merged.name
    }

    fun deleteLepaoProfile(name: String) {
        val root = getLepaoProfiles()
        root.remove(name)
        setLepaoProfiles(root)
    }

    fun getLepaoProfile(name: String): JSONObject? = getLepaoProfiles().optJSONObject(name)

    /** 同账号换票以旧会话作比较后提交；旧导出可更新资料，但不得倒灌已淘汰凭据。 */
    @Synchronized
    fun commitLepaoRenewal(name: String, previous: String, renewed: String): Boolean {
        val profile = getLepaoProfile(name) ?: return false
        if (profile.optString("session") != previous) return false
        val old = JSONObject(previous)
        val next = JSONObject(renewed)
        if (next.optString("user_id") != profile.optString("user_id") || next.optString("user_id") != old.optString("user_id")) return false
        val oldToken = old.optString("token").ifBlank { old.optString("access_token") }
        val newToken = next.optString("token").ifBlank { next.optString("access_token") }
        if (newToken.isBlank()) return false
        if (newToken != oldToken) {
            val retired = profile.optJSONArray("retired_session_hashes") ?: JSONArray()
            retired.put(LepaoApiCrypto.md5(oldToken))
            profile.put("retired_session_hashes", JSONArray((0 until retired.length()).map { retired.getString(it) }.takeLast(8)))
        }
        profile.put("session", renewed).put("session_renewed_at", System.currentTimeMillis())
        saveLepaoProfile(name, profile)
        if (lepaoActiveAccount == name && runCatching { JSONObject(lepaoSession).optString("user_id") }.getOrNull() == next.optString("user_id")) {
            lepaoSession = renewed
        }
        return true
    }

    /**
     * 乐跑账号会话（session_inject）：{"user_id":"…","token":"…","role":"0",…}，
     * 每次发布框架配置时随配置下发；Xposed 端把它写进乐跑 MMKV，等价于已登录。
     * 空串表示不注入。
     */
    var lepaoSession: String
        get() = prefs.getString("lepao_session", "") ?: ""
        set(value) = prefs.edit().putString("lepao_session", value).apply()

    /**
     * 拍照/扫脸图片替换（face_fixed_image）：默认关闭。只有用户手动开启且已选图时，
     * 乐跑相机拍照的 JPEG 回调才会被换成这张图；关闭时 hook 原样放行。
     */
    var lepaoFacePhotoEnabled: Boolean
        get() = prefs.getBoolean("lepao_face_photo_enabled", false)
        set(value) = prefs.edit().putBoolean("lepao_face_photo_enabled", value).apply()

    /** 人脸图 JPEG 的 Base64（已压缩到最长边 720px），随配置下发给乐跑进程 */
    var lepaoFacePhotoBase64: String
        get() = prefs.getString("lepao_face_photo_b64", "") ?: ""
        set(value) = prefs.edit().putString("lepao_face_photo_b64", value).apply()

    /** 最近一次从设备读到的 school_id，用于直登表单预填 */
    var lastLepaoSchoolId: String
        get() = prefs.getString("lepao_last_school_id", "") ?: ""
        set(value) = prefs.edit().putString("lepao_last_school_id", value).apply()

    /** School/nearbySchool 拉到的全量学校缓存（id/name/key/nearby） */
    var lepaoSchoolsJson: String
        get() = prefs.getString("lepao_schools_json", "") ?: ""
        set(value) = prefs.edit().putString("lepao_schools_json", value).apply()

    /** Run2/allRecordList 最近一次拉取的历史跑场缓存 */
    var lepaoRunRecordsJson: String
        get() = prefs.getString("lepao_run_records_json", "") ?: ""
        set(value) = prefs.edit().putString("lepao_run_records_json", value).apply()

    /**
     * 乐跑版本上报覆盖：空串 = 自动投递到服务端当前最低版本（4.3.3/233，实测旧版本会被拒），
     * 已安装更高版本时保持真实值；只有需要固定某个版本号时才手填。
     */
    var lepaoReportVersionCode: String
        get() = prefs.getString("lepao_report_version_code", "233") ?: "233"
        set(value) = prefs.edit().putString("lepao_report_version_code", value.trim()).apply()

    var lepaoReportVersionName: String
        get() = prefs.getString("lepao_report_version_name", "4.3.3") ?: "4.3.3"
        set(value) = prefs.edit().putString("lepao_report_version_name", value.trim()).apply()

    /** 自动结束里程（公里）：空串/0 = 关闭；Xposed 端到点对乐跑跑步页的结束按钮合成一次长按 */
    var lepaoAutoStopKm: String
        get() = prefs.getString("lepao_auto_stop_km", "") ?: ""
        set(value) = prefs.edit().putString("lepao_auto_stop_km", value.trim()).apply()

    /** 一键跑的男女目标公里：女默认 1.5、男默认 1.8，按账号身份性别自动选 */
    var lepaoRunKmMale: String
        get() = prefs.getString("lepao_run_km_male", "1.8") ?: "1.8"
        set(value) = prefs.edit().putString("lepao_run_km_male", value.trim()).apply()

    var lepaoRunKmFemale: String
        get() = prefs.getString("lepao_run_km_female", "1.5") ?: "1.5"
        set(value) = prefs.edit().putString("lepao_run_km_female", value.trim()).apply()

    /** 最近一次导出的设备指纹快照（Build 字段 / AndroidID / 屏幕参数），随档案一起搬走 */
    var lepaoDeviceFingerprint: String
        get() = prefs.getString("lepao_device_fingerprint", "") ?: ""
        set(value) = prefs.edit().putString("lepao_device_fingerprint", value).apply()

    /**
     * 乐跑档案全量备份：profiles（坐标/hook 快照/服务端数据）+ accounts + 当前会话 + 设备指纹。
     * 只在本机文件之间搬运，不上传；导入时只接受本工具的类型标识。
     */
    fun exportLepaoBackup(): String = JSONObject().apply {
        put("type", BACKUP_TYPE)
        put("version", 2)
        put("exported_at", System.currentTimeMillis())
        put("profiles", getLepaoProfiles())
        put("accounts", getLepaoAccounts())
        put("session", lepaoSession)
        // 手机型号与设备指纹随档案一起走：换机/重装后能对出这份档案原本跑在哪台机器上
        val device = DeviceFingerprint.collect(context)
        put("device", device)
        lepaoDeviceFingerprint = device.toString()
    }.toString()

    fun importLepaoBackup(json: String): Boolean {
        if (json.length > MAX_BACKUP_CHARS) return false
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return false
        }
        if (root.optString("type") != BACKUP_TYPE) return false
        val editor = prefs.edit()
        root.optJSONObject("profiles")?.let { editor.putString("lepao_profiles", it.toString()) }
        root.optJSONObject("accounts")?.let { editor.putString("lepao_accounts", it.toString()) }
        if (root.has("session")) editor.putString("lepao_session", root.optString("session", ""))
        root.optJSONObject("device")?.let { editor.putString("lepao_device_fingerprint", it.toString()) }
        return editor.commit()
    }

    private companion object {
        const val BACKUP_TYPE = "locationspoofer.lepao.backup"
        const val MAX_BACKUP_CHARS = 2_000_000
    }

    /**
     * 乐跑多账户存档：每个账户一份 {name, session, snapshot, note, created_at}。
     * session = 登录态 key 子集；snapshot = 乐跑 MMKV「setting」实例的完整 key-value
     * 备份（含跑步目标/次数/历史等 App 级持久数据），切户时整体写回。
     */
    fun getLepaoAccounts(): JSONObject {
        val raw = prefs.getString("lepao_accounts", "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun saveLepaoAccount(name: String, account: JSONObject) {
        val root = getLepaoAccounts()
        root.put(name, account)
        prefs.edit().putString("lepao_accounts", root.toString()).apply()
    }

    fun deleteLepaoAccount(name: String) {
        val root = getLepaoAccounts()
        root.remove(name)
        prefs.edit().putString("lepao_accounts", root.toString()).apply()
    }

    fun getLepaoAccount(name: String): JSONObject? = getLepaoAccounts().optJSONObject(name)

    /** 当前激活的乐跑账户名，切户时由界面写入，Xposed 端注入 MMKV 快照用 */
    var lepaoActiveAccount: String
        get() = prefs.getString("lepao_active_account", "") ?: ""
        set(value) = prefs.edit().putString("lepao_active_account", value).apply()

    /** 乐跑账户备份请求标记：非空时模块在乐跑下次启动时把全量数据 dump 到 filesDir，App 读走后置空 */
    var lepaoDumpRequest: String
        get() = prefs.getString("lepao_dump_request", "") ?: ""
        set(value) = prefs.edit().putString("lepao_dump_request", value).apply()
}
