package com.kdzw8p.mqa.v71f.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 设置页统一滑动条：只保留 Material3 的手势与无障碍行为，轨道和拇指按项目风格自绘。
 *
 * 默认 Slider 在离散 steps 下会在轨道上画一排小刻度点，内边距也和卡片圆角不匹配，
 * 看起来像"坏掉"的控件；这里用 4dp 圆头轨道 + 12dp 内缩，和路线参数滑动条保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(
        if (pressed || dragged) 18.dp else 14.dp,
        tween(100),
        label = "setting-slider-thumb"
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth().height(40.dp),
        interactionSource = interactions,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(thumbSize).background(color, CircleShape)) },
        track = { state ->
            // 不画刻度点：数值本身在右侧文字里展示，轨道只负责表达位置
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val inset = 7.dp.toPx().coerceAtMost(size.width / 2f)
                val start = Offset(if (rtl) size.width - inset else inset, center.y)
                val end = Offset(if (rtl) inset else size.width - inset, center.y)
                val span = valueRange.endInclusive - valueRange.start
                val fraction = if (span <= 0f) 0f else ((state.value - valueRange.start) / span).coerceIn(0f, 1f)
                drawLine(inactive, start, end, size.height, StrokeCap.Round)
                if (fraction > 0f) {
                    drawLine(color, start, start + (end - start) * fraction, size.height, StrokeCap.Round)
                }
            }
        }
    )
}