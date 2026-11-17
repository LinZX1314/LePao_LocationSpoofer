package com.kdzw8p.mqa.v71f.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备指纹快照：档案导出时一并写给用户，换机或重装后能对出「这份档案原本跑在哪台机器上」。
 *
 * 只读公开可得的 Build / Settings / 资源字段；serial、IMEI 这类受限字段取不到就留空，
 * 不用假值填充——导出的用途是核对，不是伪造。
 */
object DeviceFingerprint {
    fun collect(context: Context): JSONObject = JSONObject().apply {
        put("captured_at", System.currentTimeMillis())
        put("brand", Build.BRAND.orEmpty())
        put("manufacturer", Build.MANUFACTURER.orEmpty())
        put("model", Build.MODEL.orEmpty())
        put("device", Build.DEVICE.orEmpty())
        put("product", Build.PRODUCT.orEmpty())
        put("board", Build.BOARD.orEmpty())
        put("hardware", Build.HARDWARE.orEmpty())
        put("bootloader", Build.BOOTLOADER.orEmpty())
        put("display", Build.DISPLAY.orEmpty())
        put("host", Build.HOST.orEmpty())
        put("id", Build.ID.orEmpty())
        put("tags", Build.TAGS.orEmpty())
        put("type", Build.TYPE.orEmpty())
        put("radio", runCatching { Build.getRadioVersion() }.getOrNull().orEmpty())
        put("fingerprint", Build.FINGERPRINT.orEmpty())
        put("sdk_int", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE.orEmpty())
        put("incremental", Build.VERSION.INCREMENTAL.orEmpty())
        put("security_patch", Build.VERSION.SECURITY_PATCH.orEmpty())
        put("codename", Build.VERSION.CODENAME.orEmpty())
        put("supported_abis", JSONArray(Build.SUPPORTED_ABIS?.toList().orEmpty()))
        put(
            "android_id",
            runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
        )
        // Build.getSerial() 自 Android 10 起需要 READ_PHONE_STATE；取不到留空，不填假值
        put("serial", runCatching { Build.getSerial() }.getOrNull().orEmpty())
        val metrics = context.resources.displayMetrics
        put("screen_width_px", metrics.widthPixels)
        put("screen_height_px", metrics.heightPixels)
        put("screen_density", metrics.density.toDouble())
        put("screen_density_dpi", metrics.densityDpi)
        put("locale", java.util.Locale.getDefault().toLanguageTag())
        put("timezone", java.util.TimeZone.getDefault().id)
        put("is_emulator", isEmulator())
    }

    /** 只用于标注导出结果，不参与任何伪装决策 */
    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
            model.contains("sdk") || model.contains("emulator") ||
            hardware.contains("goldfish") || hardware.contains("ranchu")
    }
}
