package com.kdzw8p.mqa.v71f.ui.screen.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.utils.LepaoDirectLogin
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.importQqAndLogin
import com.kdzw8p.mqa.v71f.viewmodel.readLepaoQqAuthCapture

/** 官方手机扫码为主入口；凭据搬运仅保留为高级工具，与微信状态隔离。 */
@Composable
internal fun QqLoginPanel(
    viewModel: MainViewModel,
    schoolId: String,
    isDark: Boolean,
    onBusyChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var openid by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    var showOfficialQr by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    fun setBusy(value: Boolean) {
        busy = value
        onBusyChange(value)
    }

    fun login() {
        if (busy || imported) return
        if (openid.isBlank() || token.isBlank()) {
            message = context.getString(R.string.lepao_qq_missing_credentials)
            return
        }
        setBusy(true)
        viewModel.importQqAndLogin(openid.trim(), token.trim(), schoolId, onSuccess = { imported = true }) { text, done ->
            message = text
            if (done) {
                setBusy(false)
            }
        }
    }

    fun accept(raw: String, autoLogin: Boolean) {
        when (val outcome = LepaoDirectLogin.parseQqAuthPayload(raw)) {
            is LepaoDirectLogin.QqAuthOutcome.Success -> {
                val changed = openid != outcome.tokens.openId || token != outcome.tokens.accessToken
                openid = outcome.tokens.openId
                token = outcome.tokens.accessToken
                if (changed) imported = false
                payload = ""
                message = context.getString(R.string.lepao_qq_loaded)
                if (autoLogin) login()
            }
            is LepaoDirectLogin.QqAuthOutcome.Failure -> {
                message = context.getString(R.string.lepao_qq_invalid_payload)
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (!raw.isNullOrBlank()) accept(raw, autoLogin = true)
    }

    Column {
        Text(stringResource(R.string.lepao_qq_instructions), color = AppColors.textSecondary(isDark))
        TextButton(enabled = !busy && !imported, onClick = { showOfficialQr = true }) {
            Text(stringResource(R.string.lepao_qq_official_start))
        }
        TextButton(enabled = !busy, onClick = { advanced = !advanced }) {
            Text(stringResource(R.string.lepao_qq_advanced))
        }
        if (advanced) {
        OutlinedTextField(
            value = openid,
            onValueChange = { openid = it.trim(); imported = false },
            enabled = !busy,
            label = { Text(stringResource(R.string.lepao_qq_openid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim(); imported = false },
            enabled = !busy,
            label = { Text(stringResource(R.string.lepao_qq_access_token)) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            enabled = !busy,
            label = { Text(stringResource(R.string.lepao_qq_payload)) },
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(enabled = !busy && payload.isNotBlank(), onClick = { accept(payload, autoLogin = true) }) {
            Text(stringResource(R.string.lepao_qq_parse_login))
        }
        TextButton(enabled = !busy, onClick = {
            scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt(context.getString(R.string.lepao_qq_scan_prompt))
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }) { Text(stringResource(R.string.lepao_qq_scan)) }
        TextButton(enabled = !busy, onClick = {
            setBusy(true)
            viewModel.readLepaoQqAuthCapture { outcome ->
                setBusy(false)
                when (outcome) {
                    is LepaoDirectLogin.QqAuthOutcome.Success -> accept(
                        LepaoDirectLogin.qqAuthUri(outcome.tokens), autoLogin = false
                    )
                    is LepaoDirectLogin.QqAuthOutcome.Failure -> message = outcome.message
                }
            }
        }) { Text(stringResource(R.string.lepao_qq_read_capture)) }
        TextButton(enabled = !busy && openid.isNotBlank() && token.isNotBlank(), onClick = { showQr = true }) {
            Text(stringResource(R.string.lepao_qq_show_qr))
        }
        TextButton(enabled = !busy && !imported, onClick = { login() }) {
            Text(stringResource(
                if (busy) R.string.lepao_qq_logging_in
                else if (imported) R.string.lepao_qq_imported
                else R.string.lepao_qq_login
            ))
        }
        }
        if (imported) Text(stringResource(R.string.lepao_qq_imported))
        if (message.isNotBlank()) Text(message, color = AppColors.textSecondary(isDark))
    }

    if (showOfficialQr) {
        QqQrLoginDialog(
            isDark = isDark,
            onDismiss = { showOfficialQr = false },
            onAuthorized = { tokens ->
                showOfficialQr = false
                openid = tokens.openId
                token = tokens.accessToken
                imported = false
                login()
            }
        )
    }

    if (showQr) {
        val bitmap = remember(openid, token) {
            runCatching {
                val raw = LepaoDirectLogin.qqAuthUri(LepaoDirectLogin.QqTokens(openid, token))
                val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 640, 640)
                android.graphics.Bitmap.createBitmap(640, 640, android.graphics.Bitmap.Config.ARGB_8888).apply {
                    for (x in 0 until 640) for (y in 0 until 640) {
                        setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { showQr = false },
            containerColor = AppColors.cardBackground(isDark),
            title = { Text(stringResource(R.string.lepao_qq_show_qr)) },
            text = {
                Column {
                    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(260.dp))
                    Text(stringResource(if (bitmap == null) R.string.lepao_qq_qr_failed else R.string.lepao_qq_qr_notice))
                }
            },
            confirmButton = {
                TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
