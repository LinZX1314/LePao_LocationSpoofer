package com.kdzw8p.mqa.v71f.service

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.data.motion.MotionController
import com.kdzw8p.mqa.v71f.utils.SettingsManager
import com.kdzw8p.mqa.v71f.utils.NumericSettingInput
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** 隐形模式：静止这么久后完全透明，触摸立即显形 */
private const val GHOST_IDLE_MS = 8_000L

/** 宿主跑步页每秒广播过来的真实状态：不是模拟速度，避免悬浮窗和乐跑界面不一致。 */
data class LepaoRunStatus(val km: Double, val speed: Double, val elapsed: String, val paused: Boolean) {
    val text: String get() = String.format(Locale.US, "%.2fkm %.1fm/s", km, speed)
    val stateText: String get() = if (paused) "已暂停" else "跑步中"
}

/** 缩放通过局部 Density 改变布局和触摸坐标，不使用仅缩放绘制的 graphicsLayer。 */
@Composable
internal fun CompactJoystickOverlay(
    state: MotionController.State,
    initialCadence: Int,
    settings: SettingsManager,
    networkBlocked: Boolean,
    runStatus: LepaoRunStatus?,
    onToggleNetworkBlock: () -> Unit,
    onMoveWindow: (Float, Float) -> Unit,
    onJoystick: (Double, Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPreset: (MotionController.SpeedPreset) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onSaveRecording: (String) -> Unit,
    onDiscardRecording: () -> Unit,
    onNeedKeyboard: (Boolean) -> Unit,
    onCadenceStep: (Int) -> Unit,
    onAutoStopKm: (String) -> Unit
) {
    var collapsed by remember { mutableStateOf(settings.floatingCollapsed) }
    // 隐形模式：任何触摸都立即显形；静止 GHOST_IDLE_MS 后完全透明（不绘制 → 截图/录屏里不存在）
    var ghosted by remember { mutableStateOf(false) }
    var lastTouchAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scale by remember { mutableFloatStateOf(settings.floatingScalePercent / 100f) }
    var opacity by remember { mutableFloatStateOf(settings.floatingOpacity) }
    var cadence by remember { mutableIntStateOf(initialCadence) }
    var panel by remember { mutableStateOf<String?>(null) }
    var speeds by remember {
        mutableStateOf(MotionController.PRESETS.associate { it.name to settings.floatingSpeed(it.name, it.speedMs) })
    }
    var autoStop by remember { mutableStateOf((settings.lepaoAutoStopKm.toDoubleOrNull() ?: 0.0) > 0.0) }
    var autoStopKm by remember {
        mutableStateOf(settings.lepaoAutoStopKm.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 2.0)
    }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val latestScale by rememberUpdatedState(scale)
    val latestMove by rememberUpdatedState(onMoveWindow)
    val palette = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkPalette else LightPalette
    val drag = Modifier.pointerInput(Unit) {
        detectDragGestures { change, amount -> change.consume(); latestMove(amount.x, amount.y) }
    }
    fun setCollapsed(value: Boolean) {
        focusManager.clearFocus()
        onNeedKeyboard(false)
        collapsed = value
        settings.floatingCollapsed = value
        panel = null
    }
    fun selectPreset(preset: MotionController.SpeedPreset) {
        settings.floatingPreset = preset.name
        onPreset(preset.copy(speedMs = speeds[preset.name] ?: preset.speedMs))
    }

    /** 自动结束关掉时写空串，Xposed 端轮询到空即停用；开着时写一位小数公里。 */
    fun applyAutoStop() {
        val value = if (autoStop) String.format(Locale.US, "%.1f", autoStopKm) else ""
        settings.lepaoAutoStopKm = value
        onAutoStopKm(value)
    }
    LaunchedEffect(Unit) {
        if (state.joystickEnabled) {
            MotionController.PRESETS.firstOrNull { it.name == settings.floatingPreset }?.let { selectPreset(it) }
        }
    }
    // 隐形模式轮询：设置面板打开、待保存录制或刚触摸过时保持可见
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (!ghosted && settings.floatingGhostMode && panel == null && !state.hasPendingRecording &&
                System.currentTimeMillis() - lastTouchAt > GHOST_IDLE_MS
            ) {
                ghosted = true
            }
        }
    }
    LaunchedEffect(state.hasPendingRecording) {
        if (state.hasPendingRecording) setCollapsed(false)
    }
    DisposableEffect(Unit) { onDispose { onNeedKeyboard(false) } }

    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        Box(
            Modifier
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial 阶段只观察不消费，不抢子控件的点击/拖动
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastTouchAt = System.currentTimeMillis()
                            if (ghosted) ghosted = false
                        }
                    }
                }
                .alpha(if (ghosted) 0f else opacity)
                .clip(RoundedCornerShape(if (collapsed) 12.dp else 10.dp))
                .background(palette.card)
                .border(0.5.dp, palette.border.copy(alpha = 0.45f), RoundedCornerShape(if (collapsed) 12.dp else 10.dp))
        ) {
            if (collapsed) {
                Row(
                    Modifier.height(22.dp).width(104.dp).then(drag).clickable { setCollapsed(false) }.padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(4.dp).background(
                        if (runStatus?.paused == true || state.paused) AccentOrange else AccentBlue, CircleShape))
                    Icon(Icons.Rounded.Gamepad, stringResource(R.string.fj_expand), Modifier.size(12.dp), tint = AccentBlue)
                    Text(
                        runStatus?.text ?: String.format(Locale.US, "%.1f", state.speedMs),
                        fontSize = 9.sp, color = palette.text, maxLines = 1
                    )
                }
            } else {
                Column(Modifier.width(190.dp).padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).height(22.dp).then(drag), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DragIndicator, stringResource(R.string.fj_move), Modifier.size(12.dp), tint = palette.textSecondary)
                            Text(
                                if (runStatus != null)
                                    runStatus.stateText + " · " + runStatus.text +
                                        (if (runStatus.elapsed.isNotBlank()) " · " + runStatus.elapsed else "")
                                else stringResource(R.string.fj_speed_value, state.speedMs),
                                fontSize = 9.sp, color = palette.text, maxLines = 1
                            )
                        }
                        MiniIcon(Icons.Rounded.WifiOff, stringResource(R.string.fj_netblock), if (networkBlocked) palette.red else palette.textSecondary) {
                            panel = if (panel == "network") null else "network"
                        }
                        MiniIcon(Icons.Rounded.Opacity, stringResource(R.string.fj_opacity), palette.textSecondary) {
                            panel = if (panel == "opacity") null else "opacity"
                        }
                        MiniIcon(Icons.Rounded.Flag, stringResource(R.string.fj_autostop), if (autoStop) AccentBlue else palette.textSecondary) {
                            panel = if (panel == "autostop") null else "autostop"
                        }
                        MiniIcon(Icons.Rounded.Remove, stringResource(R.string.fj_collapse), palette.textSecondary) { setCollapsed(true) }
                    }
                    // 面板只用淡入淡出：expandVertically 会逐帧改变 overlay 窗口尺寸，
                    // WindowManager 反复 resize 造成卡顿与拖影
                    AnimatedVisibility(panel != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                        when (panel) {
                            "network" -> Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (networkBlocked) R.string.fj_netblock_on else R.string.fj_netblock_off), Modifier.weight(1f), fontSize = 10.sp, color = palette.text)
                                MiniIcon(if (networkBlocked) Icons.Rounded.ToggleOn else Icons.Rounded.ToggleOff,
                                    stringResource(R.string.fj_netblock), if (networkBlocked) palette.red else palette.textSecondary, onToggleNetworkBlock)
                            }
                            "opacity" -> CompactValueControl(
                                stringResource(R.string.fj_opacity), opacity * 100f, 40f..100f, 0,
                                onNeedKeyboard, { opacity = it / 100f; settings.floatingOpacity = opacity }
                            )
                            "autostop" -> Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(if (autoStop) R.string.fj_autostop_on else R.string.fj_autostop_off),
                                        Modifier.weight(1f), fontSize = 10.sp, color = palette.text
                                    )
                                    MiniIcon(
                                        if (autoStop) Icons.Rounded.ToggleOn else Icons.Rounded.ToggleOff,
                                        stringResource(R.string.fj_autostop), if (autoStop) AccentBlue else palette.textSecondary
                                    ) {
                                        autoStop = !autoStop
                                        applyAutoStop()
                                    }
                                }
                                CompactValueControl(
                                    stringResource(R.string.fj_autostop_km), autoStopKm.toFloat(), 0.5f..42f, 1, onNeedKeyboard
                                ) { value ->
                                    autoStopKm = value.toDouble()
                                    if (autoStop) applyAutoStop()
                                }
                                // 最低均速（分钟/公里）：模拟慢于它时宿主侧自动加速；男女分开，按账号性别选
                                CompactValueControl(
                                    "最低配速 男",
                                    (settings.lepaoMinPaceMaleSec.toFloatOrNull() ?: 320f) / 60f,
                                    4f..12f, 1, onNeedKeyboard
                                ) { value ->
                                    settings.lepaoMinPaceMaleSec = (value * 60f).roundToInt().toString()
                                }
                                CompactValueControl(
                                    "最低配速 女",
                                    (settings.lepaoMinPaceFemaleSec.toFloatOrNull() ?: 360f) / 60f,
                                    4f..12f, 1, onNeedKeyboard
                                ) { value ->
                                    settings.lepaoMinPaceFemaleSec = (value * 60f).roundToInt().toString()
                                }
                            }
                        }
                    }
                    if (state.hasPendingRecording) {
                        SaveRecordingForm(palette, onSaveRecording, onDiscardRecording, onNeedKeyboard)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                            Joystick(state.joystickEnabled, palette, onJoystick)
                            ActionButtons(state, palette, onPause, onResume, onStartRecording, onStopRecording)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            MotionController.PRESETS.forEach { preset ->
                                val selected = state.preset.name == preset.name
                                val icon = when (preset.name) {
                                    "WALKING" -> Icons.AutoMirrored.Rounded.DirectionsWalk
                                    "RUNNING" -> Icons.AutoMirrored.Rounded.DirectionsRun
                                    "CYCLING" -> Icons.AutoMirrored.Rounded.DirectionsBike
                                    "EBIKE" -> Icons.Rounded.ElectricBike
                                    else -> Icons.Rounded.DirectionsCar
                                }
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(5.dp))
                                        .background(if (selected) AccentBlue.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { selectPreset(preset) }.padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(icon, presetLabel(preset.name), Modifier.size(13.dp), tint = if (selected) AccentBlue else palette.textSecondary)
                                    Text(
                                        String.format(Locale.US, "%.1f", speeds[preset.name]),
                                        fontSize = 7.5.sp,
                                        color = if (selected) AccentBlue else palette.text,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        CompactValueControl(
                            stringResource(R.string.fj_speed_short), (speeds[state.preset.name] ?: state.preset.speedMs).toFloat(),
                            0.1f..40f, 1, onNeedKeyboard
                        ) { value ->
                            val preset = state.preset.copy(speedMs = value.toDouble())
                            speeds = speeds + (preset.name to preset.speedMs)
                            settings.saveFloatingSpeed(preset.name, preset.speedMs)
                            settings.floatingPreset = preset.name
                            onPreset(preset)
                        }
                        CompactValueControl(stringResource(R.string.fj_cadence_short), cadence.toFloat(), 60f..240f, 0, onNeedKeyboard) {
                            cadence = it.roundToInt()
                            settings.routeStepCadence = cadence
                            settings.routeAutoCadence = false
                            onCadenceStep(cadence)
                        }
                    }
                }
                // 四条边独立接收缩放手势，内部摇杆和滑块不共享拖动检测器。
                val resize: (Float) -> Unit = { pixels ->
                    scale = (latestScale + pixels / (190f * density.density)).coerceIn(0.7f, 1.5f)
                }
                // 不参与父容器测量，防止边缘的 fillMaxSize 将 WRAP_CONTENT 窗口撑满屏幕。
                Box(Modifier.matchParentSize()) {
                    ResizeEdge(Modifier.align(Alignment.CenterEnd).width(5.dp).fillMaxHeight(), 1f, false, resize) { settings.floatingScalePercent = (scale * 100).roundToInt() }
                    ResizeEdge(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxHeight(), -1f, false, resize) { settings.floatingScalePercent = (scale * 100).roundToInt() }
                    ResizeEdge(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp), -1f, true, resize) { settings.floatingScalePercent = (scale * 100).roundToInt() }
                    ResizeEdge(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp), 1f, true, resize) { settings.floatingScalePercent = (scale * 100).roundToInt() }
                }
            }
        }
    }
}

/** 边缘放大命中区以支持手指；尺寸变化不改变手势协程的key，拖动不会中途重置。 */
@Composable
private fun ResizeEdge(modifier: Modifier, sign: Float, vertical: Boolean, resize: (Float) -> Unit, commit: () -> Unit) {
    val latestResize by rememberUpdatedState(resize)
    val latestCommit by rememberUpdatedState(commit)
    Box(modifier.pointerInput(sign, vertical) {
        detectDragGestures(onDragEnd = { latestCommit() }, onDragCancel = { latestCommit() }) { change, delta ->
            change.consume()
            latestResize((if (vertical) delta.y else delta.x) * sign)
        }
    })
}

@Composable
private fun MiniIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(15.dp), tint = tint)
    }
}

/** 拖动仅在释放后提交；手输允许暂存空值/小数点，合法且有限的值才提交。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactValueControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, decimals: Int,
    keyboard: (Boolean) -> Unit, commit: (Float) -> Unit) {
    var slider by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    var text by remember(value) { mutableStateOf(String.format(Locale.US, if (decimals == 0) "%.0f" else "%.1f", value)) }
    var focused by remember { mutableStateOf(false) }
    var invalid by remember(value) { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val thumbSize by animateDpAsState(if (dragged || pressed) 12.dp else 10.dp, tween(100), label = "slider-thumb")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val activeColor = AccentBlue
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val focus = LocalFocusManager.current
    fun applyText(): Boolean {
        val parsed = NumericSettingInput.parse(text, range.start, range.endInclusive, decimals == 0)
            ?.let { NumericSettingInput.normalize(it, range.start, range.endInclusive, decimals) }
        if (parsed == null) {
            invalid = true
            return false
        }
        slider = parsed
        invalid = false
        commit(parsed)
        text = String.format(Locale.US, if (decimals == 0) "%.0f" else "%.1f", parsed)
        return true
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column {
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 8.sp, modifier = Modifier.width(20.dp), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = slider,
                onValueChange = { value ->
                    NumericSettingInput.normalize(value, range.start, range.endInclusive, decimals)?.let { normalized ->
                        slider = normalized
                        invalid = false
                        text = String.format(Locale.US, if (decimals == 0) "%.0f" else "%.1f", normalized)
                    }
                },
                onValueChangeFinished = { focus.clearFocus(); commit(slider) },
                valueRange = range,
                interactionSource = interactions,
                modifier = Modifier.weight(1f).height(24.dp),
                thumb = { Box(Modifier.size(thumbSize).background(activeColor, CircleShape)) },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(2.5.dp)) {
                        val inset = 5.dp.toPx().coerceAtMost(size.width / 2f)
                        val start = Offset(if (rtl) size.width - inset else inset, center.y)
                        val end = Offset(if (rtl) inset else size.width - inset, center.y)
                        val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        drawLine(inactiveColor, start, end, size.height, StrokeCap.Round)
                        if (fraction > 0f) drawLine(activeColor, start, start + (end - start) * fraction, size.height, StrokeCap.Round)
                    }
                }
            )
            BasicTextField(
                text, { candidate -> text = candidate.take(8); invalid = false }, singleLine = true,
                textStyle = TextStyle(
                    color = if (invalid) MaterialTheme.colorScheme.error else activeColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End
                ),
                cursorBrush = SolidColor(activeColor),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (applyText()) focus.clearFocus() }),
                modifier = Modifier.width(32.dp).height(20.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (focused) activeColor.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) applyText()
                        focused = state.isFocused
                        keyboard(state.isFocused)
                    }
            )
        }
        if (invalid) {
            Text(stringResource(R.string.fj_invalid_range, range.start.toString(), range.endInclusive.toString()),
                color = MaterialTheme.colorScheme.error, fontSize = 7.5.sp, maxLines = 1)
        }
        }
    }
}
