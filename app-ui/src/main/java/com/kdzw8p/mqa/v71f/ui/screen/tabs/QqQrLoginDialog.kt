package com.kdzw8p.mqa.v71f.ui.screen.tabs

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin
import com.kdzw8p.mqa.v71f.utils.QqQrLogin
import kotlinx.coroutines.launch

/** 直接展示腾讯生成并轮询的二维码；手机只需 QQ，无需乐跑或模块。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun QqQrLoginDialog(
    isDark: Boolean,
    onAuthorized: (LepaoDirectLogin.QqTokens) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(QqQrLogin.Session()) }
    var consumed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(context.getString(R.string.lepao_qq_official_wait)) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var active by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            active = false
            browser?.apply { stopLoading(); destroy() }
            browser = null
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = AppColors.cardBackground(isDark),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.lepao_qq_official_title))
                Text(stringResource(R.string.lepao_qq_official_instructions), fontSize = 12.sp)
                // WebView 只占剩余空间，高密度屏幕上也要保留刷新与关闭操作。
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            browser = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url.toString()
                                    if (session.isCallback(url)) {
                                        if (!active || consumed) return true
                                        consumed = true
                                        view.stopLoading()
                                        val token = session.accessToken(url)
                                        if (token == null) {
                                            status = context.getString(R.string.lepao_qq_official_callback_failed)
                                        } else {
                                            status = context.getString(R.string.lepao_qq_official_resolving)
                                            val currentSession = session
                                            scope.launch {
                                                val tokens = QqQrLogin.resolve(token)
                                                if (active && currentSession === session) {
                                                    if (tokens == null) status = context.getString(R.string.lepao_qq_official_identity_failed)
                                                    else onAuthorized(tokens)
                                                }
                                            }
                                        }
                                        return true
                                    }
                                    val uri = Uri.parse(url)
                                    val host = uri.host.orEmpty()
                                    // 不启动 QQ Intent，也不让授权页导航到第三方域或明文站点。
                                    return uri.scheme != "https" || !(host == "qq.com" || host.endsWith(".qq.com") ||
                                        host == "gtimg.cn" || host.endsWith(".gtimg.cn") ||
                                        host == "gtimg.com" || host.endsWith(".gtimg.com"))
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame && active && !consumed) {
                                        status = context.getString(R.string.lepao_qq_official_load_failed)
                                    }
                                }
                            }
                            loadUrl(session.url)
                        }
                    }
                )
                Text(status, fontSize = 12.sp)
                Row {
                    TextButton(onClick = {
                        browser?.stopLoading()
                        session = QqQrLogin.Session()
                        consumed = false
                        status = context.getString(R.string.lepao_qq_official_wait)
                        browser?.loadUrl(session.url)
                    }) { Text(stringResource(R.string.lepao_qq_official_refresh)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}
