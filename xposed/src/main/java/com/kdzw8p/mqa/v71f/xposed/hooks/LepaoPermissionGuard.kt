@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "DEPRECATION",
    "unused",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName")

package com.kdzw8p.mqa.v71f.xposed.hooks

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.kdzw8p.mqa.v71f.xposed.LocationHooker
import com.kdzw8p.mqa.v71f.xposed.utils.XposedBridge
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers

/**
 * 乐跑宿主的权限与警告弹窗守卫。
 *
 * 为什么单独一个文件：AntiDetectionHooker 里的 checkSelfPermission 谎报只对「非乐跑包」安装，
 * 乐跑是爱加密壳，早期装 AppOps / Settings.Secure / PackageManager 那套会在
 * InitializationProvider 阶段把壳的类加载打崩（见 LocationHooker 里 skipAntiDetect 的说明）。
 * 本文件的所有 hook 都只在 LATE ATTACH（首个 Activity.onResume 之后）安装，壳已完成解密，
 * 因此可以安全地补上乐跑缺失的那一层。
 *
 * 覆盖三件事：
 * 1. 定位权限自检恒真（申请周期内 App 不再进入「必要权限未开启」分支）；
 * 2. requestPermissions 不再拉起系统权限框，直接回填「已授予」给调用方；
 * 3. 已知的告警弹窗（必要权限 / 悬浮窗 / GPS 开关 / 作弊嫌疑 / 跑区异常）在 show 前拦掉。
 */
internal fun LocationHooker.installLepaoPermissionGuard(classLoader: ClassLoader): Int {
    var installed = 0
    installed += hookFakeLocationPermission(classLoader)
    installed += hookLocationAppOps(classLoader)
    installed += hookPermissionRequest(classLoader)
    installed += suppressWarningDialogs(classLoader)
    XposedBridge.log("[R7VqzN] lepao permission guard installed=$installed")
    return installed
}

/** 只谎报定位相关权限：这几个由本模块自己在定位层兜住，谎报后不会打到真实系统服务上。 */
private val FAKE_LOCATION_PERMISSIONS = setOf(
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION"
)

/**
 * 不谎报 CAMERA / RECORD_AUDIO / READ_PHONE_STATE / 存储权限：
 * 它们背后有真实的驱动或 provider 校验，谎报只会把 App 推到一条必然抛 SecurityException 的分支上
 * （AntiDetectionHooker 里 BLUETOOTH_CONNECT 的教训）。
 */

private fun LocationHooker.spoofActive(): Boolean =
    readConfig()?.optBoolean("active", false) == true

private fun LocationHooker.hookFakeLocationPermission(classLoader: ClassLoader): Int {
    var count = 0
    val classes = listOfNotNull(
        XposedHelpers.findClassIfExists("android.content.Context", classLoader),
        XposedHelpers.findClassIfExists("android.content.ContextWrapper", classLoader),
        XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader)
    )
    for (clazz in classes) {
        for (name in arrayOf("checkSelfPermission", "checkPermission", "checkCallingOrSelfPermission", "checkCallingPermission")) {
            count += XposedHelpers.hookAllMethods(clazz, name) { chain, _ ->
                if (!spoofActive()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                val perm = chain.args.firstOrNull { it is String } as? String
                if (perm != null && FAKE_LOCATION_PERMISSIONS.contains(perm)) {
                    return@hookAllMethods PackageManager.PERMISSION_GRANTED
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }.size
        }
    }
    return count
}

/** 0 = MODE_ALLOWED。定位 op 之外的查询原样放行，避免影响广告 SDK 自己的统计。 */
private fun LocationHooker.hookLocationAppOps(classLoader: ClassLoader): Int {
    val appOps = XposedHelpers.findClassIfExists("android.app.AppOpsManager", classLoader) ?: return 0
    var count = 0
    for (name in arrayOf(
        "checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow",
        "unsafeCheckOp", "unsafeCheckOpNoThrow"
    )) {
        count += XposedHelpers.hookAllMethods(appOps, name) { chain, _ ->
            if (!spoofActive()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (isLocationOp(chain.args.firstOrNull())) {
                return@hookAllMethods 0
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }.size
    }
    return count
}

private fun isLocationOp(op: Any?): Boolean = when (op) {
    is Int -> op == 0 || op == 1 || op == 2 || op == 12 || op == 41 || op == 42
    is String -> op == "android:coarse_location" ||
            op == "android:fine_location" ||
            op == "android:gps" ||
            op == "android:monitor_location" ||
            op == "android:monitor_location_high_power"
    else -> false
}

/**
 * 不调用原方法，直接按「全部授予」回调 onRequestPermissionsResult。
 * 回调必须走 Activity 的虚方法：ComponentActivity 覆写了它并转发给 ActivityResultRegistry，
 * 所以 registerForActivityResult 那套也能拿到 granted，不会卡在等待。
 */
private fun LocationHooker.hookPermissionRequest(classLoader: ClassLoader): Int {
    val activityClass = XposedHelpers.findClassIfExists("android.app.Activity", classLoader) ?: return 0
    return XposedHelpers.hookAllMethods(activityClass, "requestPermissions") { chain, _ ->
        if (!spoofActive()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        val activity = chain.thisObject as? Activity
        val permissions = (chain.args.getOrNull(0) as? Array<*>)
            ?.mapNotNull { it as? String }
            ?.toTypedArray()
        val requestCode = chain.args.getOrNull(1) as? Int ?: 0
        if (activity == null || permissions == null || permissions.isEmpty()) {
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        val results = IntArray(permissions.size) { PackageManager.PERMISSION_GRANTED }
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val callback = activity.javaClass.methods.firstOrNull {
                    it.name == "onRequestPermissionsResult" && it.parameterCount == 3
                } ?: return@runCatching
                callback.isAccessible = true
                callback.invoke(activity, requestCode, permissions, results)
            }.onFailure {
                XposedBridge.log("[R7VqzN] permission callback failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        XposedBridge.log("[R7VqzN] permission auto-granted: ${permissions.joinToString(",")}")
        return@hookAllMethods null
    }.size
}

/**
 * 只拦乐跑自己的告警文案，白名单式匹配，绝不按「包含 确定/是」这种宽条件拦，
 * 否则会把到点结束确认框一起干掉（LepaoAutoStop 依赖那个框）。
 */
private val WARNING_KEYWORDS = listOf(
    "必要权限",
    "请开启悬浮窗权限",
    "悬浮窗权限",
    "系统检测到您关闭了GPS",
    "操作gps开关过于频繁",
    "作弊嫌疑",
    "只开放线下打卡跑区"
)

private fun LocationHooker.suppressWarningDialogs(classLoader: ClassLoader): Int {
    if (readConfig()?.optBoolean("suppress_warning_dialogs", true) == false) return 0
    val dialogClass = XposedHelpers.findClassIfExists("android.app.Dialog", classLoader) ?: return 0
    return XposedHelpers.hookAllMethods(dialogClass, "show") { chain, _ ->
        val dialog = chain.thisObject as? android.app.Dialog
        val text = dialog?.let { collectDialogText(it) }.orEmpty()
        val hit = WARNING_KEYWORDS.firstOrNull { text.contains(it) }
        if (hit != null) {
            XposedBridge.log("[R7VqzN] warning dialog suppressed: '$hit'")
            return@hookAllMethods null
        }
        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
    }.size
}

private fun collectDialogText(dialog: android.app.Dialog): String {
    val root = runCatching { dialog.window?.peekDecorView() }.getOrNull()
        ?: runCatching { dialog.findViewById<View>(android.R.id.content) }.getOrNull()
        ?: return ""
    val sb = StringBuilder()
    appendText(root, sb, 0)
    return sb.toString()
}

private fun appendText(view: View?, sb: StringBuilder, depth: Int) {
    if (view == null || depth > 24) return
    if (view is TextView) {
        val text = view.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) sb.append(text).append('\n')
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            appendText(view.getChildAt(i), sb, depth + 1)
        }
    }
}
