package com.kdzw8p.mqa.v71f.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.BitmapFactory

private val avatarClient = OkHttpClient()

/**
 * 乐跑服务端头像。URL 来自 user_avatar_url / User/myZone，是带 Expires 的 OSS 签名地址，
 * 过期后重新拉取会失败，所以按“去掉 query 的 URL”做本地磁盘缓存：地址刷新也复用同一张图。
 * 缓存和网络都失败时退回名称首字色块。
 */
@Composable
fun RemoteAvatar(
    url: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    fallbackColor: Color = Color(0xFF3B82F6)
) {
    val context = LocalContext.current
    val cacheFile = remember(url) {
        val key = url.substringBefore('?').hashCode()
        File(context.cacheDir, "lepao_avatar_$key.img")
    }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            val cached = if (cacheFile.exists() && cacheFile.length() > 0) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.asImageBitmap()
            } else {
                null
            }
            if (cached != null) return@withContext cached

            runCatching {
                if (url.isBlank()) return@runCatching null
                if (!url.startsWith("http")) {
                    BitmapFactory.decodeFile(url)?.asImageBitmap()
                } else {
                    val request = Request.Builder().url(url).build()
                    avatarClient.newCall(request).execute().use { response ->
                        val body = response.body ?: return@use null
                        val bytes = body.bytes()
                        if (bytes.isEmpty()) return@use null
                        cacheFile.writeBytes(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }
    val shape = CircleShape
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape)
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(fallbackColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                fallbackText.take(1).uppercase(),
                fontSize = (size.value * 0.45f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}