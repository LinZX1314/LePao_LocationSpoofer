package com.kdzw8p.mqa.v71f.data.repository

import com.kdzw8p.mqa.v71f.data.model.SavedLocation
import com.kdzw8p.mqa.v71f.data.model.SavedRoute
import com.kdzw8p.mqa.v71f.utils.SettingsManager

class SettingsRepository(private val settingsManager: SettingsManager) {

    var routeRunMode: String
        get() = settingsManager.routeRunMode
        set(value) { settingsManager.routeRunMode = value }

    var routeSimMode: String
        get() = settingsManager.routeSimMode
        set(value) { settingsManager.routeSimMode = value }

    var routeCustomSpeedMs: Double
        get() = settingsManager.routeCustomSpeedMs
        set(value) { settingsManager.routeCustomSpeedMs = value }

    var routeStepCadence: Int
        get() = settingsManager.routeStepCadence
        set(value) { settingsManager.routeStepCadence = value }

    var routeUseReal: Boolean
        get() = settingsManager.routeUseReal
        set(value) { settingsManager.routeUseReal = value }

    var routeStopAtDestination: Boolean
        get() = settingsManager.routeStopAtDestination
        set(value) { settingsManager.routeStopAtDestination = value }

    var routeEnableSteps: Boolean
        get() = settingsManager.routeEnableSteps
        set(value) { settingsManager.routeEnableSteps = value }

    var routeAutoCadence: Boolean
        get() = settingsManager.routeAutoCadence
        set(value) { settingsManager.routeAutoCadence = value }


    fun getSavedLocations(): List<SavedLocation> = settingsManager.getSavedLocations()

    fun setSavedLocations(locations: List<SavedLocation>) = settingsManager.saveLocationList(locations)

    fun addSavedLocation(location: SavedLocation) = settingsManager.addSavedLocation(location)

    fun removeSavedLocation(location: SavedLocation) = settingsManager.removeSavedLocation(location)

    fun getSavedRoutes(): List<SavedRoute> = settingsManager.getSavedRoutes()

    fun addSavedRoute(route: SavedRoute) = settingsManager.addSavedRoute(route)

    fun removeSavedRoute(route: SavedRoute) = settingsManager.removeSavedRoute(route)

    fun isLanguageSet(): Boolean = settingsManager.isLanguageSet

    fun setLanguageSet(value: Boolean) {
        settingsManager.isLanguageSet = value
    }

    fun getLanguage(): String = settingsManager.language

    fun setLanguage(value: String) {
        settingsManager.language = value
    }

    fun getAmapApiKey(): String = settingsManager.amapApiKey

    fun setAmapApiKey(value: String) {
        settingsManager.amapApiKey = value
    }

    fun getBaiduApiKey(): String = settingsManager.baiduApiKey

    fun setBaiduApiKey(value: String) {
        settingsManager.baiduApiKey = value
    }

    fun getGoogleApiKey(): String = settingsManager.googleApiKey

    fun setGoogleApiKey(value: String) {
        settingsManager.googleApiKey = value
    }

    fun getWigleApiToken(): String = settingsManager.wigleApiToken

    fun setWigleApiToken(value: String) {
        settingsManager.wigleApiToken = value
    }

    fun getOpencellidApiToken(): String = settingsManager.opencellidApiToken

    fun setOpencellidApiToken(value: String) {
        settingsManager.opencellidApiToken = value
    }

    fun getMapType(): String = settingsManager.mapType

    fun setMapType(value: String) {
        settingsManager.mapType = value
    }

    fun getMapEngine(): String = settingsManager.mapEngine

    fun setMapEngine(value: String) {
        settingsManager.mapEngine = value
    }

    fun getRootSolution(): String = settingsManager.rootSolution

    fun setRootSolution(value: String) {
        settingsManager.rootSolution = value
    }

    fun getVendorOverride(): String = settingsManager.vendorOverride

    fun setVendorOverride(value: String) {
        settingsManager.vendorOverride = value
    }

    var debugDumpSystemServices: Boolean
        get() = settingsManager.debugDumpSystemServices
        set(value) {
            settingsManager.debugDumpSystemServices = value
        }

    fun getIgnoredVersion(): String = settingsManager.ignoredVersion

    fun setIgnoredVersion(value: String) {
        settingsManager.ignoredVersion = value
    }

    fun getAppCoordinateSystems(): Map<String, String> = settingsManager.getAppCoordinateSystems()

    fun setAppCoordinateSystems(map: Map<String, String>) =
        settingsManager.setAppCoordinateSystems(map)

    fun getSystemHookPackages(): Set<String> = settingsManager.getSystemHookPackages()

    fun isHookGroupEnabled(pkg: String, group: String): Boolean =
        settingsManager.isHookGroupEnabled(pkg, group)

    fun setHookGroupEnabled(pkg: String, group: String, enabled: Boolean) =
        settingsManager.setHookGroupEnabled(pkg, group, enabled)

    fun getTargetHookGroupsJson(): org.json.JSONObject = settingsManager.getTargetHookGroupsJson()

    fun setSystemHookPackages(packages: Set<String>) =
        settingsManager.setSystemHookPackages(packages)

    var isSystemHookGlobalMode: Boolean
        get() = settingsManager.isSystemHookGlobalMode
        set(value) {
            settingsManager.isSystemHookGlobalMode = value
        }

    var forceLocationEnabled: Boolean
        get() = settingsManager.forceLocationEnabled
        set(value) { settingsManager.forceLocationEnabled = value }

    var isSpoofingActive: Boolean
        get() = settingsManager.isSpoofingActive
        set(value) {
            settingsManager.isSpoofingActive = value
        }

    var lastSpoofedLat: String
        get() = settingsManager.lastSpoofedLat
        set(value) {
            settingsManager.lastSpoofedLat = value
        }

    var lastSpoofedLng: String
        get() = settingsManager.lastSpoofedLng
        set(value) {
            settingsManager.lastSpoofedLng = value
        }

    var mockWifi: Boolean
        get() = settingsManager.mockWifi
        set(value) {
            settingsManager.mockWifi = value
        }

    var mockCell: Boolean
        get() = settingsManager.mockCell
        set(value) {
            settingsManager.mockCell = value
        }

    var mockBluetooth: Boolean
        get() = settingsManager.mockBluetooth
        set(value) {
            settingsManager.mockBluetooth = value
        }

    var enableJitter: Boolean
        get() = settingsManager.enableJitter
        set(value) {
            settingsManager.enableJitter = value
        }

    var restartAppsOnSpoof: Boolean
        get() = settingsManager.restartAppsOnSpoof
        set(value) {
            settingsManager.restartAppsOnSpoof = value
        }

    var altitude: String
        get() = settingsManager.altitude
        set(value) {
            settingsManager.altitude = value
        }

    var altitudeVariationM: Int
        get() = settingsManager.altitudeVariationM
        set(value) {
            settingsManager.altitudeVariationM = value
        }

    var realismLevel: Int
        get() = settingsManager.realismLevel
        set(value) {
            settingsManager.realismLevel = value
        }

    var speedFluctuationPct: Int
        get() = settingsManager.speedFluctuationPct
        set(value) {
            settingsManager.speedFluctuationPct = value
        }

    var realismSwayScalePct: Int
        get() = settingsManager.realismSwayScalePct
        set(value) {
            settingsManager.realismSwayScalePct = value
        }

    var realismRouteOffsetM: Int
        get() = settingsManager.realismRouteOffsetM
        set(value) {
            settingsManager.realismRouteOffsetM = value
        }

    var slowdownPct: Int
        get() = settingsManager.slowdownPct
        set(value) {
            settingsManager.slowdownPct = value
        }

    var slowdownMinSec: Int
        get() = settingsManager.slowdownMinSec
        set(value) {
            settingsManager.slowdownMinSec = value
        }

    var slowdownMaxSec: Int
        get() = settingsManager.slowdownMaxSec
        set(value) {
            settingsManager.slowdownMaxSec = value
        }

    var slowdownGapSec: Int
        get() = settingsManager.slowdownGapSec
        set(value) {
            settingsManager.slowdownGapSec = value
        }

    var slowdownRampSec: Int
        get() = settingsManager.slowdownRampSec
        set(value) {
            settingsManager.slowdownRampSec = value
        }

    var cadenceDipPct: Int
        get() = settingsManager.cadenceDipPct
        set(value) {
            settingsManager.cadenceDipPct = value
        }

    var gaitTemplate: String
        get() = settingsManager.gaitTemplate
        set(value) {
            settingsManager.gaitTemplate = value
        }

    var useGaitTemplate: Boolean
        get() = settingsManager.useGaitTemplate
        set(value) {
            settingsManager.useGaitTemplate = value
        }

    var keepLastMapPosition: Boolean
        get() = settingsManager.keepLastMapPosition
        set(value) {
            settingsManager.keepLastMapPosition = value
        }

    /** 最后一次选定的地图位置（GCJ-02），未保存过时返回 null */
    fun getLastMapPosition(): Pair<String, String>? {
        val lat = settingsManager.lastMapLat
        val lng = settingsManager.lastMapLng
        return if (lat.isNotBlank() && lng.isNotBlank()) lat to lng else null
    }

    fun setLastMapPosition(lat: String, lng: String) {
        settingsManager.lastMapLat = lat
        settingsManager.lastMapLng = lng
    }

    var satelliteCount: String
        get() = settingsManager.satelliteCount
        set(value) {
            settingsManager.satelliteCount = value
        }

    var checkBetaUpdates: Boolean
        get() = settingsManager.checkBetaUpdates
        set(value) {
            settingsManager.checkBetaUpdates = value
        }

    var uiScalePercent: Int
        get() = settingsManager.uiScalePercent
        set(value) {
            settingsManager.uiScalePercent = value
        }

    // 乐跑多用户配置（profiles）：每个账号一份定位与 hook 组快照
    fun getLepaoProfiles(): org.json.JSONObject = settingsManager.getLepaoProfiles()

    fun getLepaoProfile(name: String): org.json.JSONObject? = settingsManager.getLepaoProfile(name)

    fun saveLepaoProfile(name: String, profile: org.json.JSONObject) {
        settingsManager.saveLepaoProfile(name, profile)
    }

    fun mergeLepaoSocialProfile(provider: String, openId: String, authorized: org.json.JSONObject): String =
        settingsManager.mergeLepaoSocialProfile(provider, openId, authorized)

    fun deleteLepaoProfile(name: String) {
        settingsManager.deleteLepaoProfile(name)
    }

    /** 乐跑账号会话注入的 JSON（{"user_id","token","role",...}），空串不注入 */
    var lepaoSession: String
        get() = settingsManager.lepaoSession
        set(value) { settingsManager.lepaoSession = value }

    /** 乐跑版本上报覆盖，空串=按真实版本上报 */
    var lepaoReportVersionCode: String
        get() = settingsManager.lepaoReportVersionCode
        set(value) { settingsManager.lepaoReportVersionCode = value }

    var lepaoReportVersionName: String
        get() = settingsManager.lepaoReportVersionName
        set(value) { settingsManager.lepaoReportVersionName = value }

    /** 自动结束里程（公里），空串/0 = 关闭 */
    var lepaoAutoStopKm: String
        get() = settingsManager.lepaoAutoStopKm
        set(value) { settingsManager.lepaoAutoStopKm = value }

    /** 一键跑按性别取的目标公里：男 1.8 / 女 1.5 */
    var lepaoRunKmMale: String
        get() = settingsManager.lepaoRunKmMale
        set(value) { settingsManager.lepaoRunKmMale = value }

    /** 最低平均配速（秒/公里）：男/女分开，模拟均速低于它时自动加速 */
    var lepaoMinPaceMaleSec: String
        get() = settingsManager.lepaoMinPaceMaleSec
        set(value) { settingsManager.lepaoMinPaceMaleSec = value }

    var lepaoMinPaceFemaleSec: String
        get() = settingsManager.lepaoMinPaceFemaleSec
        set(value) { settingsManager.lepaoMinPaceFemaleSec = value }

    /** 最近一次开始模拟用的路线点串，供批量跑恢复 */
    var lepaoLastRouteJson: String
        get() = settingsManager.lepaoLastRouteJson
        set(value) { settingsManager.lepaoLastRouteJson = value }

    var lepaoRunKmFemale: String
        get() = settingsManager.lepaoRunKmFemale
        set(value) { settingsManager.lepaoRunKmFemale = value }

    var lepaoFacePhotoEnabled: Boolean
        get() = settingsManager.lepaoFacePhotoEnabled
        set(value) { settingsManager.lepaoFacePhotoEnabled = value }

    var lepaoFacePhotoBase64: String
        get() = settingsManager.lepaoFacePhotoBase64
        set(value) { settingsManager.lepaoFacePhotoBase64 = value }

    var lastLepaoSchoolId: String
        get() = settingsManager.lastLepaoSchoolId
        set(value) { settingsManager.lastLepaoSchoolId = value }

    var lepaoSchoolsJson: String
        get() = settingsManager.lepaoSchoolsJson
        set(value) { settingsManager.lepaoSchoolsJson = value }

    var lepaoRunRecordsJson: String
        get() = settingsManager.lepaoRunRecordsJson
        set(value) { settingsManager.lepaoRunRecordsJson = value }

    fun exportLepaoBackup(): String = settingsManager.exportLepaoBackup()

    fun importLepaoBackup(json: String): Boolean = settingsManager.importLepaoBackup(json)

    /** 乐跑多账户存档（含 MMKV 完整备份快照） */
    fun getLepaoAccounts(): org.json.JSONObject = settingsManager.getLepaoAccounts()

    fun saveLepaoAccount(name: String, account: org.json.JSONObject) {
        settingsManager.saveLepaoAccount(name, account)
    }

    fun getLepaoAccount(name: String): org.json.JSONObject? = settingsManager.getLepaoAccount(name)

    fun deleteLepaoAccount(name: String) {
        settingsManager.deleteLepaoAccount(name)
    }

    var lepaoActiveAccount: String
        get() = settingsManager.lepaoActiveAccount
        set(value) { settingsManager.lepaoActiveAccount = value }

    /** 乐跑账户备份请求：非空时模块在乐跑下次启动把全量数据 dump 到 filesDir */
    var lepaoDumpRequest: String
        get() = settingsManager.lepaoDumpRequest
        set(value) { settingsManager.lepaoDumpRequest = value }
}
