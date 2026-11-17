package com.kdzw8p.mqa.v71f.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kdzw8p.mqa.v71f.data.model.AppState
import com.kdzw8p.mqa.v71f.data.model.GaitRecordingState
import com.kdzw8p.mqa.v71f.ui.R
import com.kdzw8p.mqa.v71f.ui.components.SettingSlider
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AccentGreen
import com.kdzw8p.mqa.v71f.ui.theme.AccentOrange
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.ui.theme.noRippleClickable
import com.kdzw8p.mqa.v71f.utils.GaitTemplate
import com.kdzw8p.mqa.v71f.utils.MotionRealism
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.cancelGaitRecording
import com.kdzw8p.mqa.v71f.viewmodel.deleteGaitTemplate
import com.kdzw8p.mqa.v71f.viewmodel.dismissGaitRecordingResult
import com.kdzw8p.mqa.v71f.viewmodel.setRealismLevel
import com.kdzw8p.mqa.v71f.viewmodel.setRealismRouteOffsetM
import com.kdzw8p.mqa.v71f.viewmodel.setRealismSwayScalePct
import com.kdzw8p.mqa.v71f.viewmodel.setCadenceDipPct
import com.kdzw8p.mqa.v71f.viewmodel.setIsAutoCadence
import com.kdzw8p.mqa.v71f.viewmodel.setSlowdownGapSec
import com.kdzw8p.mqa.v71f.viewmodel.setSlowdownMaxSec
import com.kdzw8p.mqa.v71f.viewmodel.setSlowdownMinSec
import com.kdzw8p.mqa.v71f.viewmodel.setSlowdownPct
import com.kdzw8p.mqa.v71f.viewmodel.setSlowdownRampSec
import com.kdzw8p.mqa.v71f.viewmodel.setSpeedFluctuationPct
import com.kdzw8p.mqa.v71f.viewmodel.setStepCadenceSpm
import com.kdzw8p.mqa.v71f.viewmodel.setUseGaitTemplate
import com.kdzw8p.mqa.v71f.viewmodel.startGaitRecording
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun MotionRealismScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    // 录制时手机放进口袋，保持屏幕常亮，避免熄屏后传感器数据中断
    val recording = uiState.gaitRecording
    val keepAwake = recording is GaitRecordingState.Countdown || recording is GaitRecordingState.Recording
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                            shape = CircleShape
                        )
                        .noRippleClickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = if (isDark) Color.White else Color(0xFF1A1D20),
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.motion_realism_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.motion_realism_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RealismLevelCard(uiState, isDark) { viewModel.setRealismLevel(it) }
                SpeedFluctuationCard(uiState) { viewModel.setSpeedFluctuationPct(it) }
                PathShapeCard(
                    uiState = uiState,
                    isDark = isDark,
                    onSwayChange = { viewModel.setRealismSwayScalePct(it) },
                    onOffsetChange = { viewModel.setRealismRouteOffsetM(it) }
                )
                SlowdownCard(
                    uiState = uiState,
                    isDark = isDark,
                    onPctChange = { viewModel.setSlowdownPct(it) },
                    onMinChange = { viewModel.setSlowdownMinSec(it) },
                    onMaxChange = { viewModel.setSlowdownMaxSec(it) },
                    onGapChange = { viewModel.setSlowdownGapSec(it) },
                    onRampChange = { viewModel.setSlowdownRampSec(it) }
                )
                CadenceCard(viewModel, uiState, isDark)
                GaitCard(viewModel, uiState, isDark)
                HintRow(stringResource(R.string.realism_next_session_hint), isDark)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CardHeader(icon: ImageVector, tint: Color, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun RealismLevelCard(uiState: AppState, isDark: Boolean, onSelect: (Int) -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Shuffle,
            AccentBlue,
            stringResource(R.string.realism_level_title),
            stringResource(R.string.realism_level_desc)
        )
        Spacer(Modifier.height(14.dp))
        val levels = listOf(
            MotionRealism.Level.OFF to stringResource(R.string.realism_level_off),
            MotionRealism.Level.LOW to stringResource(R.string.realism_level_low),
            MotionRealism.Level.MEDIUM to stringResource(R.string.realism_level_medium),
            MotionRealism.Level.HIGH to stringResource(R.string.realism_level_high)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            levels.forEach { (level, label) ->
                val selected = uiState.realismLevel == level.id
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) AccentBlue
                            else if (isDark) Color.White.copy(alpha = 0.05f)
                            else Color.Black.copy(alpha = 0.04f)
                        )
                        .noRippleClickable { onSelect(level.id) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedFluctuationCard(uiState: AppState, onChange: (Int) -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Speed,
            AccentOrange,
            stringResource(R.string.speed_fluctuation_title),
            stringResource(R.string.speed_fluctuation_desc)
        )
        Spacer(Modifier.height(8.dp))
        var local by remember(uiState.speedFluctuationPct) { mutableFloatStateOf(uiState.speedFluctuationPct.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingSlider(
                value = local,
                onValueChange = { local = it },
                valueRange = 0f..MotionRealism.MAX_SPEED_FLUCTUATION_PCT.toFloat(),
                color = AccentOrange,
                modifier = Modifier.weight(1f),
                onValueChangeFinished = { onChange(local.roundToInt()) }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (uiState.slowdownPct > 0 && uiState.slowdownMinSec > 0 && uiState.slowdownMaxSec > 0)
                    "−${local.roundToInt()}%" else stringResource(R.string.speed_fluctuation_value, local.roundToInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentOrange,
                modifier = Modifier.widthIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun PathShapeCard(
    uiState: AppState,
    isDark: Boolean,
    onSwayChange: (Int) -> Unit,
    onOffsetChange: (Int) -> Unit
) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Timeline,
            AccentOrange,
            stringResource(R.string.path_shape_title),
            stringResource(R.string.path_shape_desc)
        )
        Spacer(Modifier.height(8.dp))

        // 抖动幅度：0% = 完全贴线，100% = 现有默认摆动，>100% 更明显地偏离中线
        var sway by remember(uiState.realismSwayScalePct) {
            mutableFloatStateOf(uiState.realismSwayScalePct.toFloat())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.path_sway_label),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.width(72.dp)
            )
            SettingSlider(
                value = sway,
                onValueChange = { sway = it },
                valueRange = 0f..MotionRealism.MAX_SWAY_SCALE_PCT.toFloat(),
                color = AccentOrange,
                modifier = Modifier.weight(1f),
                onValueChangeFinished = { onSwayChange(sway.roundToInt()) }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${sway.roundToInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentOrange,
                modifier = Modifier.widthIn(min = 48.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        // 路线整体平移：正值沿行进方向向右、负值向左，只改形状，不改长度
        var offset by remember(uiState.realismRouteOffsetM) {
            mutableFloatStateOf(uiState.realismRouteOffsetM.toFloat())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.path_offset_label),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.width(72.dp)
            )
            SettingSlider(
                value = offset,
                onValueChange = { offset = it },
                valueRange = 0f..MotionRealism.MAX_ROUTE_OFFSET_ABS_M.toFloat(),
                color = AccentBlue,
                modifier = Modifier.weight(1f),
                onValueChangeFinished = { onOffsetChange(offset.roundToInt()) }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "±${offset.roundToInt()} m",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentBlue,
                modifier = Modifier.widthIn(min = 48.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        HintRow(stringResource(R.string.path_shape_hint), isDark)
    }
}

@Composable
private fun SlowdownCard(
    uiState: AppState,
    isDark: Boolean,
    onPctChange: (Int) -> Unit,
    onMinChange: (Int) -> Unit,
    onMaxChange: (Int) -> Unit,
    onGapChange: (Int) -> Unit,
    onRampChange: (Int) -> Unit
) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Timeline,
            AccentBlue,
            stringResource(R.string.slowdown_title),
            stringResource(R.string.slowdown_desc)
        )
        Spacer(Modifier.height(10.dp))

        var pct by remember(uiState.slowdownPct) { mutableFloatStateOf(uiState.slowdownPct.toFloat()) }
        var minSec by remember(uiState.slowdownMinSec) { mutableFloatStateOf(uiState.slowdownMinSec.toFloat()) }
        var maxSec by remember(uiState.slowdownMaxSec) { mutableFloatStateOf(uiState.slowdownMaxSec.toFloat()) }
        var gapSec by remember(uiState.slowdownGapSec) { mutableFloatStateOf(uiState.slowdownGapSec.toFloat()) }
        var rampSec by remember(uiState.slowdownRampSec) { mutableFloatStateOf(uiState.slowdownRampSec.toFloat()) }

        SliderSetting(
            label = stringResource(R.string.slowdown_pct_label),
            valueText = stringResource(R.string.slowdown_pct_value, pct.roundToInt()),
            value = pct,
            range = 0f..MotionRealism.MAX_SLOWDOWN_PCT.toFloat(),
            color = AccentOrange,
            onValueChange = { pct = it },
            onCommit = { onPctChange(pct.roundToInt()) }
        )
        SliderSetting(
            label = stringResource(R.string.slowdown_min_label),
            valueText = stringResource(R.string.slowdown_sec_value, minSec.roundToInt()),
            value = minSec,
            range = MotionRealism.MIN_SLOWDOWN_MIN_SEC.toFloat()..MotionRealism.MAX_SLOWDOWN_MIN_SEC.toFloat(),
            color = AccentBlue,
            onValueChange = { minSec = it },
            onCommit = { onMinChange(minSec.roundToInt()) }
        )
        SliderSetting(
            label = stringResource(R.string.slowdown_max_label),
            valueText = stringResource(R.string.slowdown_sec_value, maxSec.roundToInt()),
            value = maxSec,
            range = MotionRealism.MIN_SLOWDOWN_MAX_SEC.toFloat()..MotionRealism.MAX_SLOWDOWN_MAX_SEC.toFloat(),
            color = AccentBlue,
            onValueChange = { maxSec = it },
            onCommit = { onMaxChange(maxSec.roundToInt()) }
        )
        SliderSetting(
            label = stringResource(R.string.slowdown_gap_label),
            valueText = stringResource(R.string.slowdown_sec_value, gapSec.roundToInt()),
            value = gapSec,
            range = MotionRealism.MIN_SLOWDOWN_GAP_SEC.toFloat()..MotionRealism.MAX_SLOWDOWN_GAP_SEC.toFloat(),
            color = AccentGreen,
            onValueChange = { gapSec = it },
            onCommit = { onGapChange(gapSec.roundToInt()) }
        )
        SliderSetting(
            label = stringResource(R.string.slowdown_ramp_label),
            valueText = if (rampSec.roundToInt() == 0) stringResource(R.string.slowdown_ramp_auto)
                else stringResource(R.string.slowdown_sec_value, rampSec.roundToInt()),
            value = rampSec,
            range = 0f..MotionRealism.MAX_SLOWDOWN_RAMP_SEC.toFloat(),
            color = AccentOrange,
            onValueChange = { rampSec = it },
            onCommit = { onRampChange(rampSec.roundToInt()) }
        )

        Spacer(Modifier.height(10.dp))
        HintRow(stringResource(R.string.slowdown_hint), isDark)
    }
}

/** 步频模拟：全局步频基准（固定 / 自适应）与降速段内的步频缓降比例 */
@Composable
private fun CadenceCard(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean
) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.MonitorHeart,
            AccentOrange,
            stringResource(R.string.step_cadence_simulation),
            stringResource(R.string.cadence_card_desc)
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.smart_adaptive_cadence),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Switch(
                checked = uiState.isAutoCadence,
                onCheckedChange = { viewModel.setIsAutoCadence(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentGreen
                )
            )
        }

        if (!uiState.isAutoCadence) {
            var cadence by remember(uiState.stepCadenceSpm) { mutableFloatStateOf(uiState.stepCadenceSpm.toFloat()) }
            SliderSetting(
                label = stringResource(R.string.set_cadence),
                valueText = stringResource(R.string.cadence_spm_value, cadence.roundToInt()),
                value = cadence,
                range = 60f..240f,
                color = AccentBlue,
                onValueChange = { cadence = it },
                onCommit = { viewModel.setStepCadenceSpm(cadence.roundToInt()) }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(-5, -1, 1, 5).forEach { step ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f))
                            .noRippleClickable { viewModel.setStepCadenceSpm(uiState.stepCadenceSpm + step) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (step > 0) "+$step" else "$step",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        var dipPct by remember(uiState.cadenceDipPct) { mutableFloatStateOf(uiState.cadenceDipPct.toFloat()) }
        SliderSetting(
            label = stringResource(R.string.cadence_dip_label),
            valueText = stringResource(R.string.slowdown_pct_value, dipPct.roundToInt()),
            value = dipPct,
            range = 0f..MotionRealism.MAX_CADENCE_DIP_PCT.toFloat(),
            color = AccentGreen,
            onValueChange = { dipPct = it },
            onCommit = { viewModel.setCadenceDipPct(dipPct.roundToInt()) }
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text(valueText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
        SettingSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            color = color,
            onValueChangeFinished = onCommit
        )
    }
}

@Composable
private fun GaitCard(viewModel: MainViewModel, uiState: AppState, isDark: Boolean) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.AutoMirrored.Rounded.DirectionsWalk,
            AccentGreen,
            stringResource(R.string.gait_title),
            stringResource(R.string.gait_desc)
        )
        Spacer(Modifier.height(12.dp))

        when (val recording = uiState.gaitRecording) {
            is GaitRecordingState.Countdown, is GaitRecordingState.Recording, GaitRecordingState.Processing -> {
                val text = when (recording) {
                    is GaitRecordingState.Countdown -> stringResource(R.string.gait_countdown_format, recording.secondsLeft)
                    is GaitRecordingState.Recording -> stringResource(R.string.gait_recording)
                    else -> stringResource(R.string.gait_processing)
                }
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                if (recording is GaitRecordingState.Recording) {
                    LinearProgressIndicator(
                        progress = { recording.progress },
                        color = AccentGreen,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(color = AccentGreen, modifier = Modifier.fillMaxWidth())
                }
                if (recording !is GaitRecordingState.Processing) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { viewModel.cancelGaitRecording() }) {
                        Text(stringResource(R.string.gait_cancel))
                    }
                }
            }

            is GaitRecordingState.Failed -> {
                val reason = when (recording.reason) {
                    GaitTemplate.Reason.TOO_SHORT -> stringResource(R.string.gait_failed_too_short)
                    GaitTemplate.Reason.NO_RHYTHM -> stringResource(R.string.gait_failed_no_rhythm)
                    GaitTemplate.Reason.TOO_FEW_STRIDES -> stringResource(R.string.gait_failed_too_few_strides)
                }
                Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.startGaitRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) { Text(stringResource(R.string.gait_retry)) }
                    OutlinedButton(onClick = { viewModel.dismissGaitRecordingResult() }) {
                        Text(stringResource(R.string.gait_cancel))
                    }
                }
            }

            GaitRecordingState.Idle -> {
                val cadence = uiState.gaitTemplateCadence
                Text(
                    text = if (cadence != null) {
                        stringResource(R.string.gait_recorded_format, cadence, uiState.gaitTemplateStrides)
                    } else {
                        stringResource(R.string.gait_not_recorded)
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                if (cadence != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.gait_use_template),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = uiState.useGaitTemplate, onCheckedChange = { viewModel.setUseGaitTemplate(it) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.startGaitRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text(stringResource(if (cadence != null) R.string.gait_rerecord else R.string.gait_record))
                    }
                    if (cadence != null) {
                        OutlinedButton(onClick = { viewModel.deleteGaitTemplate() }) {
                            Text(stringResource(R.string.gait_delete))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HintRow(stringResource(R.string.gait_scope_hint), isDark)
    }
}

@Composable
private fun HintRow(text: String, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Rounded.Info,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
