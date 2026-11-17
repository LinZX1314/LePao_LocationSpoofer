package com.kdzw8p.mqa.v71f.di

import com.kdzw8p.mqa.v71f.data.db.AppDatabase
import com.kdzw8p.mqa.v71f.data.motion.ConfigMotionSink
import com.kdzw8p.mqa.v71f.data.motion.MotionController
import com.kdzw8p.mqa.v71f.data.state.SpoofingState
import com.kdzw8p.mqa.v71f.data.repository.LocationRepository
import com.kdzw8p.mqa.v71f.data.repository.SettingsRepository
import com.kdzw8p.mqa.v71f.data.repository.WifiRepository
import com.kdzw8p.mqa.v71f.utils.ConfigManager
import com.kdzw8p.mqa.v71f.utils.EnvironmentScanner
import com.kdzw8p.mqa.v71f.utils.LSPosedManager
import com.kdzw8p.mqa.v71f.utils.OpenCellIdClient
import com.kdzw8p.mqa.v71f.utils.RootManager
import com.kdzw8p.mqa.v71f.utils.SettingsManager
import com.kdzw8p.mqa.v71f.utils.WigleClient
import com.kdzw8p.mqa.v71f.data.repository.HookStatusRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreDataModule = module {
    single { RootManager() }
    single { com.kdzw8p.mqa.v71f.utils.NetworkBlockManager(androidContext()) }
    single { ConfigManager(androidContext(), get()) }
    single { LSPosedManager() }
    single { SettingsManager(androidContext()) }
    single { EnvironmentScanner(androidContext()) }

    single { WigleClient() }
    single { OpenCellIdClient() }
    single { WifiRepository(get()) }

    single { LocationRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { SettingsRepository(get()) }
    single { HookStatusRepository(get()) }
    single {
        val settings = get<SettingsManager>()
        MotionController(
            sink = ConfigMotionSink(get(), get()),
            realismParams = {
                (SpoofingState.realismLevel.takeIf { it >= 0 } ?: settings.realismLevel) to
                    (SpoofingState.speedFluctuationPct.takeIf { it >= 0 } ?: settings.speedFluctuationPct)
            },
            slowdownParams = {
                com.kdzw8p.mqa.v71f.utils.MotionRealism.SlowdownSettings(
                    SpoofingState.slowdownPct.takeIf { it >= 0 } ?: settings.slowdownPct,
                    SpoofingState.slowdownMinSec.takeIf { it >= 0 } ?: settings.slowdownMinSec,
                    SpoofingState.slowdownMaxSec.takeIf { it >= 0 } ?: settings.slowdownMaxSec,
                    SpoofingState.slowdownGapSec.takeIf { it >= 0 } ?: settings.slowdownGapSec,
                    SpoofingState.slowdownRampSec.takeIf { it >= 0 } ?: settings.slowdownRampSec
                )
            },
            cadenceDipPct = {
                SpoofingState.cadenceDipPct.takeIf { it >= 0 } ?: settings.cadenceDipPct
            }
        )
    }

    single { AppDatabase.getDatabase(androidContext()) }
    single { get<AppDatabase>().environmentDao() }
    single { get<AppDatabase>().savedRouteDao() }
}
