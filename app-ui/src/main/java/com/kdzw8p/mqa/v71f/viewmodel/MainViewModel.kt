package com.kdzw8p.mqa.v71f.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdzw8p.mqa.v71f.data.db.CompleteLocation
import com.kdzw8p.mqa.v71f.data.db.EnvironmentDao
import com.kdzw8p.mqa.v71f.data.db.LocationRecord
import com.kdzw8p.mqa.v71f.data.model.AppState
import com.kdzw8p.mqa.v71f.data.model.AppMapType
import com.kdzw8p.mqa.v71f.data.model.MapEngine
import com.kdzw8p.mqa.v71f.data.model.RootSolution
import com.kdzw8p.mqa.v71f.vendor.VendorScheme
import com.kdzw8p.mqa.v71f.utils.GaitTemplate
import com.kdzw8p.mqa.v71f.data.motion.MotionController
import com.kdzw8p.mqa.v71f.data.repository.LocationRepository
import com.kdzw8p.mqa.v71f.data.repository.SettingsRepository
import com.kdzw8p.mqa.v71f.data.repository.WifiRepository
import com.kdzw8p.mqa.v71f.data.state.SpoofingState
import com.kdzw8p.mqa.v71f.ui.screen.spoofing.SpoofingUiState
import com.kdzw8p.mqa.v71f.utils.EnvironmentScanner
import com.kdzw8p.mqa.v71f.utils.LSPosedManager
import com.kdzw8p.mqa.v71f.utils.OpenCellIdClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

sealed interface FavoriteToggleResult {
    data class Added(val name: String) : FavoriteToggleResult
    data class Removed(val name: String) : FavoriteToggleResult
    data object Failed : FavoriteToggleResult
}

class MainViewModel(
    internal val locationRepository: LocationRepository,
    internal val settingsRepository: SettingsRepository,
    internal val lsposedManager: LSPosedManager,
    internal val environmentScanner: EnvironmentScanner,
    internal val environmentDao: EnvironmentDao,
    internal val wifiRepository: WifiRepository,
    internal val opencellidClient: OpenCellIdClient,
    internal val motionController: MotionController,
    internal val context: Context
) : ViewModel() {
    internal var lastMapMoveTime = 0L
    internal var mapMoveJob: Job? = null
    internal var gaitRecordingJob: Job? = null
    internal var mockCapabilitiesJob: Job? = null
    internal var environmentUpdateJob: Job? = null

    val collectionRoutes = environmentDao.observeCollectionRoutes().distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val environmentLocations: StateFlow<List<CompleteLocation>> = environmentDao
        .observeAllCompleteLocations()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    internal val _uiState = MutableStateFlow(
        AppState(
            mapType = try {
                AppMapType.valueOf(settingsRepository.getMapType())
            } catch (e: Exception) {
                AppMapType.NORMAL
            },
            mapEngine = try {
                MapEngine.valueOf(settingsRepository.getMapEngine())
            } catch (e: Exception) {
                MapEngine.AUTO
            },
            rootSolution = try {
                RootSolution.valueOf(settingsRepository.getRootSolution())
            } catch (e: Exception) {
                RootSolution.AUTO
            },
            vendorScheme = VendorScheme.fromId(settingsRepository.getVendorOverride()),
            debugDumpSystemServices = settingsRepository.debugDumpSystemServices,
            routeRunMode = runCatching { com.kdzw8p.mqa.v71f.data.model.RouteRunMode.valueOf(settingsRepository.routeRunMode) }
                .getOrDefault(com.kdzw8p.mqa.v71f.data.model.RouteRunMode.MANUAL),
            routeSimMode = runCatching { com.kdzw8p.mqa.v71f.data.model.SimMode.valueOf(settingsRepository.routeSimMode) }
                .getOrDefault(com.kdzw8p.mqa.v71f.data.model.SimMode.RUNNING),
            customSpeedMs = settingsRepository.routeCustomSpeedMs,
            stepCadenceSpm = settingsRepository.routeStepCadence,
            isAutoCadence = settingsRepository.routeAutoCadence,
            useRealRoute = settingsRepository.routeUseReal,
            stopAtDestination = settingsRepository.routeStopAtDestination,
            enableStepSimulation = settingsRepository.routeEnableSteps,
            realismLevel = settingsRepository.realismLevel,
            speedFluctuationPct = settingsRepository.speedFluctuationPct,
            realismSwayScalePct = settingsRepository.realismSwayScalePct,
            realismRouteOffsetM = settingsRepository.realismRouteOffsetM,
            slowdownPct = settingsRepository.slowdownPct,
            slowdownMinSec = settingsRepository.slowdownMinSec,
            slowdownMaxSec = settingsRepository.slowdownMaxSec,
            slowdownGapSec = settingsRepository.slowdownGapSec,
            slowdownRampSec = settingsRepository.slowdownRampSec,
            cadenceDipPct = settingsRepository.cadenceDipPct,
            gaitTemplateCadence = GaitTemplate.decode(settingsRepository.gaitTemplate)?.cadenceSpm,
            gaitTemplateStrides = GaitTemplate.decode(settingsRepository.gaitTemplate)?.strideCount ?: 0,
            useGaitTemplate = settingsRepository.useGaitTemplate,
            keepLastMapPosition = settingsRepository.keepLastMapPosition,
            savedLocations = settingsRepository.getSavedLocations(),
            savedRoutes = emptyList(), // 将由 Room Flow 填充
            currentLanguage = settingsRepository.getLanguage(),
            isLanguageSet = settingsRepository.isLanguageSet(),
            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
            systemHookPackages = settingsRepository.getSystemHookPackages(),
            isSystemHookGlobalMode = settingsRepository.isSystemHookGlobalMode,
            forceLocationEnabled = settingsRepository.forceLocationEnabled,
            mockWifi = settingsRepository.mockWifi,
            mockCell = settingsRepository.mockCell,
            mockBluetooth = settingsRepository.mockBluetooth,
            enableJitter = settingsRepository.enableJitter,
            restartAppsOnSpoof = settingsRepository.restartAppsOnSpoof,
            altitudeInput = settingsRepository.altitude,
            altitudeVariationM = settingsRepository.altitudeVariationM,
            satelliteCountInput = settingsRepository.satelliteCount,
            wigleToken = settingsRepository.getWigleApiToken(),
            opencellidToken = settingsRepository.getOpencellidApiToken()
        )
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    internal val _spoofingUiState =
        MutableStateFlow(SpoofingUiState())
    val spoofingUiState: StateFlow<SpoofingUiState> =
        _spoofingUiState.asStateFlow()

    internal var locationSyncJob: Job? = null
    internal var continuousScanJob: Job? = null

    init {
        initialize()
    }

    data class ClusterData(
        val center: LocationRecord,
        var count: Int,
        var hasWifi: Boolean,
        var hasBluetooth: Boolean,
        var hasCell: Boolean
    )

    internal var pinnedLocationRecordId: Long? = null

    internal val favoriteToggleMutex = kotlinx.coroutines.sync.Mutex()

    internal var lastEnvironmentRouteId: Long? = null
    internal var lastDbQueryLat: Double = 0.0
    internal var lastDbQueryLng: Double = 0.0
}
