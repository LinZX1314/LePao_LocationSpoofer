package com.kdzw8p.mqa.v71f

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.google.android.libraries.places.api.Places
import com.kdzw8p.mqa.v71f.di.appModules
import com.kdzw8p.mqa.v71f.utils.XposedModuleStatus
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class LocationApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        val customApiKey = prefs.getString("amap_api_key", "")
        if (!customApiKey.isNullOrEmpty()) {
            MapsInitializer.setApiKey(customApiKey)
            AMapLocationClient.setApiKey(customApiKey)
            ServiceSettings.getInstance().setApiKey(customApiKey)
        }

        val baiduApiKey = prefs.getString("baidu_api_key", "")
        if (!baiduApiKey.isNullOrEmpty()) {
            com.baidu.mapapi.SDKInitializer.setApiKey(baiduApiKey)
        }

        try {
            com.baidu.mapapi.SDKInitializer.setAgreePrivacy(this, true)
            com.baidu.mapapi.SDKInitializer.setCoordType(com.baidu.mapapi.CoordType.GCJ02)
            com.baidu.mapapi.SDKInitializer.initialize(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val googleApiKey = prefs.getString("google_api_key", "")
        if (!Places.isInitialized() && !googleApiKey.isNullOrBlank()) {
            try {
                Places.initialize(this, googleApiKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        startKoin {
            androidLogger()
            androidContext(this@LocationApp)
            modules(appModules)
        }

        // Create the config publisher before registering; it observes the current service and rebinds.
        org.koin.java.KoinJavaComponent.get<com.kdzw8p.mqa.v71f.utils.ConfigManager>(
            com.kdzw8p.mqa.v71f.utils.ConfigManager::class.java
        )

        // 启动时清理孤儿断网规则：iptables 规则在内核里，卸装/升级/进程被杀都不会自动消失。
        // 这里按注释标记无差别清理，保证「只要 App 能启动一次」就不会出现残留规则把别的 App 网掐死。
        // 清理是幂等的，本来没规则时什么也不做。
        Thread {
            try {
                val nbm = org.koin.java.KoinJavaComponent.get<com.kdzw8p.mqa.v71f.utils.NetworkBlockManager>(
                    com.kdzw8p.mqa.v71f.utils.NetworkBlockManager::class.java
                )
                org.koin.java.KoinJavaComponent.get<com.kdzw8p.mqa.v71f.utils.RootManager>(
                    com.kdzw8p.mqa.v71f.utils.RootManager::class.java
                )
                kotlinx.coroutines.runBlocking { nbm.purgeOrphans() }
            } catch (e: Throwable) {
                android.util.Log.w("R7VqzN", "启动清理断网规则失败（无 root 时忽略）", e)
            }
        }.start()
        Thread {
            try {
                XposedServiceHelper.registerListener(this@LocationApp)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onServiceBind(service: XposedService) {
        XposedModuleStatus.update(service)
    }

    override fun onServiceDied(service: XposedService) {
        XposedModuleStatus.clear(service)
    }
}
