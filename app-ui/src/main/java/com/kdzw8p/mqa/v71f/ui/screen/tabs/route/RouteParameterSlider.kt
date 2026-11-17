package com.kdzw8p.mqa.v71f.ui.screen.tabs.route

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.utils.NumericSettingInput

/** 保留标准Slider手势和无障碍行为，只替换轨道外观并规范提交精度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteParameterSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(if (pressed || dragged) 18.dp else 14.dp, tween(100), label = "route-slider-thumb")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = { input ->
            NumericSettingInput.normalize(input, valueRange.start, valueRange.endInclusive, decimals)?.let(onValueChange)
        },
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth().height(40.dp),
        interactionSource = interactions,
        thumb = { Box(Modifier.size(thumbSize).background(AccentBlue, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val inset = 7.dp.toPx().coerceAtMost(size.width / 2f)
                val start = Offset(if (rtl) size.width - inset else inset, center.y)
                val end = Offset(if (rtl) inset else size.width - inset, center.y)
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                drawLine(inactive, start, end, size.height, StrokeCap.Round)
                if (fraction > 0f) drawLine(AccentBlue, start, start + (end - start) * fraction, size.height, StrokeCap.Round)
            }
        }
    )
}
