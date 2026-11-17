package com.kdzw8p.mqa.v71f.xposed.hooks

import android.hardware.Camera
import android.util.Base64
import com.kdzw8p.mqa.v71f.xposed.LocationHooker
import com.kdzw8p.mqa.v71f.xposed.utils.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import org.json.JSONObject

/**
 * 人脸照片替换（face_fixed_image）。
 *
 * 乐跑的人脸核验链路：CameraActivity 用 Camera1 打开摄像头 → `Camera.takePicture` 的 JPEG 回调
 * 拿到 byte[] → 存文件 → 取 OSS STS 上传 → `Run2/faceVerify`（face_file + run_face_verify_id）。
 * 在 JPEG 回调处换掉字节，后续保存、上传、核验全部仍走乐跑自己的链路，不伪造核验结果。
 *
 * 只在配置 `face_photo_enabled=true` 且用户选过图片时生效；关闭时 hook 原样放行。
 */
internal fun LocationHooker.hookFacePhotoReplacement(classLoader: ClassLoader): Int {
    val cameraClass = XposedHelpers.findClassIfExists("android.hardware.Camera", classLoader) ?: return 0
    var hooked = 0
    for (method in cameraClass.declaredMethods) {
        if (method.name != "takePicture") continue
        if (method.parameterTypes.none { Camera.PictureCallback::class.java.isAssignableFrom(it) }) continue
        try {
            method.isAccessible = true
            XposedHelpers.module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val config = readConfig()
                    val jpeg = if (config != null && config.optBoolean("face_photo_enabled", false)) {
                        facePhotoBytes(config)
                    } else {
                        null
                    }
                    if (jpeg == null) return chain.proceed(chain.args.toTypedArray())

                    val args = chain.args.toTypedArray()
                    // takePicture 有两个 PictureCallback：前面是 raw（通常为 null），最后一个是 JPEG
                    for (i in args.indices.reversed()) {
                        val callback = args[i]
                        if (callback is Camera.PictureCallback) {
                            args[i] = jpegCallback(callback, jpeg)
                            break
                        }
                    }
                    return chain.proceed(args)
                }
            })
            hooked++
        } catch (_: Throwable) {
        }
    }
    return hooked
}

private fun jpegCallback(target: Camera.PictureCallback, jpeg: ByteArray): Camera.PictureCallback {
    return Proxy.newProxyInstance(
        Camera.PictureCallback::class.java.classLoader,
        arrayOf(Camera.PictureCallback::class.java)
    ) { _, method, args ->
        if (method.name == "onPictureTaken") {
            val forwarded = args?.copyOf() ?: arrayOfNulls<Any>(0)
            if (forwarded.isNotEmpty()) forwarded[0] = jpeg
            method.invoke(target, *forwarded)
        } else {
            method.invoke(target, *(args ?: emptyArray()))
        }
    } as Camera.PictureCallback
}

// 配置轮询每秒解析一次 JSON，图片字节只在 Base64 变化时解码一次
@Volatile private var facePhotoSource: String? = null
@Volatile private var facePhotoJpeg: ByteArray? = null

private fun facePhotoBytes(config: JSONObject): ByteArray? {
    val source = config.optString("face_photo_b64", "")
    if (source.isBlank()) return null
    if (source == facePhotoSource) return facePhotoJpeg
    val decoded = try {
        Base64.decode(source, Base64.DEFAULT)
    } catch (_: Throwable) {
        null
    }
    facePhotoSource = source
    facePhotoJpeg = decoded
    return decoded
}