package com.kdzw8p.mqa.v71f.ui.screen.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kdzw8p.mqa.v71f.data.model.AppMapType
import com.kdzw8p.mqa.v71f.data.model.MapEngine
import com.kdzw8p.mqa.v71f.ui.components.AppMapController
import com.kdzw8p.mqa.v71f.ui.components.AppMapView
import com.kdzw8p.mqa.v71f.ui.components.MarkerType
import com.kdzw8p.mqa.v71f.ui.theme.AccentBlue
import com.kdzw8p.mqa.v71f.ui.theme.AppColors
import com.kdzw8p.mqa.v71f.utils.LepaoRunTrack
import kotlin.math.ceil
import kotlin.math.max

private val SlowColor = Color(0xFF1E9E60)
private val MidColor = Color(0xFFFFD028)
private val FastColor = Color(0xFFF04438)
private val LegendBrush = Brush.horizontalGradient(listOf(SlowColor, MidColor, FastColor))

private enum class PreviewTab(val label: String) { TRACK("轨迹"), DETAIL("详情") }

/**
 * 历史路径全屏预览：轨迹页用高德底图按速度着色，详情页给分公里配速和步频曲线。
 * 色带与配速由分钟级步数剖面推算，界面注明估算。
 */
@Composable
internal fun RunPathPreviewDialog(
    title: String, busy: Boolean, error: String, result: LepaoRunTrack.Result?,
    fallbackDistance: String, fallbackPace: String, fallbackDate: String,
    isDark: Boolean, onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(PreviewTab.TRACK) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                PreviewHeader(title, tab, isDark, { tab = it }, onDismiss)
                when {
                    busy -> Center("加载中…", isDark)
                    error.isNotBlank() -> Center("读取失败：$error", isDark, Color(0xFFE5534B))
                    result == null -> Center("这条记录没有可用的轨迹点位", isDark)
                    tab == PreviewTab.TRACK -> TrackPage(result, fallbackDistance, fallbackPace, fallbackDate, isDark)
                    else -> DetailPage(result, isDark)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text("关闭", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    title: String, tab: PreviewTab, isDark: Boolean,
    onSelect: (PreviewTab) -> Unit, onBack: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.width(36.dp).clickable(onClick = onBack)
            )
            Text(
                title.ifBlank { "历史路径" }, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = AppColors.textSecondary(isDark), modifier = Modifier.weight(1f), maxLines = 1
            )
            PreviewTab.values().forEach { t ->
                TextButton(onClick = { onSelect(t) }) {
                    Text(
                        t.label, fontSize = 14.sp,
                        fontWeight = if (t == tab) FontWeight.Bold else FontWeight.Normal,
                        color = if (t == tab) SlowColor else AppColors.textSecondary(isDark)
                    )
                }
            }
        }
        HorizontalDivider(color = AppColors.textSecondary(isDark).copy(alpha = 0.15f))
    }
}

@Composable
private fun Center(text: String, isDark: Boolean, color: Color? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = color ?: AppColors.textSecondary(isDark))
    }
}

@Composable
private fun ColumnScope.TrackPage(
    r: LepaoRunTrack.Result, fallbackDistance: String, fallbackPace: String,
    fallbackDate: String, isDark: Boolean
) {
    var controller by remember { mutableStateOf<AppMapController?>(null) }
    var satellite by remember { mutableStateOf(false) }
    LaunchedEffect(satellite, controller) {
        controller?.setMapType(if (satellite) AppMapType.SATELLITE else AppMapType.NORMAL)
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
        if (r.track.size < 2) {
            Center("该记录没有可连线的轨迹（来源 ${r.trackSource}），先刷新列表再试", isDark)
        } else {
            AppMapView(
                mapEngine = MapEngine.AUTO,
                isDomestic = true,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { c -> controller = c; drawTrack(c, r) }
            )
            MapControls(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                satellite = satellite,
                onZoomIn = { zoomBy(controller, 1f) },
                onZoomOut = { zoomBy(controller, -1f) },
                onToggleSatellite = { satellite = !satellite }
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = (if (isDark) Color(0xFF161B22) else Color.White).copy(alpha = 0.92f)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("慢", fontSize = 10.sp, color = SlowColor)
                        Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(4.dp).background(LegendBrush, RoundedCornerShape(2.dp)))
                        Text("快", fontSize = 10.sp, color = FastColor)
                    }
                    Text(
                        "轨迹 ${r.track.size} 点 · ${r.trackSource} · 打卡 ${r.checkInCount}；" +
                            if (r.speedFromTrack) "速度取自记录" else "色带为估算",
                        fontSize = 9.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 4.dp)
                    )
                    if (r.track.size < 10 && r.candidates.isNotEmpty()) {
                        Text(
                            "服务端可选源 " + r.candidates.joinToString(" · ") { "${it.first} ${it.second}" },
                            fontSize = 8.sp, color = AppColors.textSecondary(isDark), maxLines = 2
                        )
                    }
                    if (r.track.size < 10 && r.fields.isNotEmpty()) {
                        Text(
                            "响应字段 " + r.fields.joinToString(" · "),
                            fontSize = 8.sp, color = AppColors.textSecondary(isDark), maxLines = 4
                        )
                        Text(
                            r.fileInfo, fontSize = 8.sp, color = AppColors.textSecondary(isDark), maxLines = 1
                        )
                    }
                }
            }
        }
    }
    val s = r.summary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (s.distanceKm > 0) "%.2f".format(s.distanceKm) else fallbackDistance.ifBlank { "--" },
                fontSize = 40.sp, fontWeight = FontWeight.Bold
            )
            Text("公里", fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                buildString {
                    append("步道乐跑·")
                    append(LepaoRunTrack.formatDate(r.startTimeSec).ifBlank { fallbackDate })
                },
                fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.weight(1f)
            )
        }
        if (r.failedReason.isNotBlank()) {
            Text("状态：${r.failedReason}", fontSize = 10.sp, color = Color(0xFFD29922))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = AppColors.textSecondary(isDark).copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth()) {
            Metric(
                LepaoRunTrack.formatPace(s.avgPaceSecPerKm).takeIf { it != "--" } ?: fallbackPace.ifBlank { "--" },
                "平均配速", Modifier.weight(1f)
            )
            Metric(LepaoRunTrack.formatDuration(s.timeSec), "用时", Modifier.weight(1f))
            Metric(r.checkInCount.toString(), "已打卡", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 10.sp, color = Color(0xFF8B949E))
    }
}

@Composable
private fun MapControls(
    modifier: Modifier, satellite: Boolean,
    onZoomIn: () -> Unit, onZoomOut: () -> Unit, onToggleSatellite: () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MapButton("+", onZoomIn)
        MapButton("−", onZoomOut)
        MapButton(if (satellite) "标准" else "卫星", onToggleSatellite)
    }
}

@Composable
private fun MapButton(text: String, onClick: () -> Unit) {
    val wide = text.length > 1
    Surface(
        shape = CircleShape,
        color = Color(0xE6111827),
        modifier = Modifier.size(if (wide) 46.dp else 40.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text, fontSize = if (wide) 11.sp else 20.sp,
                fontWeight = FontWeight.Bold, color = Color.White
            )
        }
    }
}

private fun zoomBy(controller: AppMapController?, delta: Float) {
    val c = controller ?: return
    val zoom = c.cameraZoom ?: return
    val lat = c.cameraTargetLat ?: return
    val lng = c.cameraTargetLng ?: return
    c.moveCamera(lat, lng, (zoom + delta).coerceIn(3f, 20f))
}

private fun drawTrack(controller: AppMapController, r: LepaoRunTrack.Result) {
    controller.clear()
    controller.disableUiControls()
    r.bands.forEach { controller.addPolyline(it.points, it.color, 9f) }
    r.checkpoints.forEachIndexed { i, p ->
        val type = when {
            i == 0 -> MarkerType.GREEN
            i == r.checkpoints.lastIndex -> MarkerType.RED
            else -> MarkerType.DEFAULT
        }
        controller.addMarker(p.lat, p.lng, p.label, type)
    }
    controller.fitBounds(r.track.map { it.lat to it.lng }, 60, 60, 60, 160)
}

@Composable
private fun DetailPage(r: LepaoRunTrack.Result, isDark: Boolean) {
    val s = r.summary
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("配速", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("平均配速 ${LepaoRunTrack.formatPace(s.avgPaceSecPerKm)}", fontSize = 11.sp, color = AppColors.textSecondary(isDark))
            Text(
                "最快配速 ${LepaoRunTrack.formatPace(s.fastestPaceSecPerKm)}", fontSize = 11.sp,
                color = AppColors.textSecondary(isDark), modifier = Modifier.padding(start = 12.dp)
            )
        }
        if (r.splits.isEmpty()) {
            Text("没有分公里数据", fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 8.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("公里", fontSize = 12.sp, modifier = Modifier.width(44.dp))
                Text("配速", fontSize = 12.sp, color = AppColors.textSecondary(isDark))
            }
            val worst = r.splits.maxOf { it.paceSecPerKm }
            r.splits.forEach { sp ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (sp.partial) "不足" else sp.km.toString(),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp)
                    )
                    Box(
                        Modifier.weight(1f).height(26.dp)
                            .background(splitColor(sp.paceSecPerKm, worst), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            LepaoRunTrack.formatPace(sp.paceSecPerKm), fontSize = 12.sp,
                            color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
                if (sp.partial) {
                    Text(
                        "最后不足一公里，配速${LepaoRunTrack.formatPace(sp.paceSecPerKm)}",
                        fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(start = 44.dp, top = 4.dp)
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = AppColors.textSecondary(isDark).copy(alpha = 0.12f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("步数:${s.stepNum}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("⚠ 受手机硬件限制，步频数据仅供参考", fontSize = 10.sp, color = Color(0xFFD29922))
        }
        if (r.samples.size >= 2) {
            Text("步/分钟", fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 10.dp))
            MetricChart(r.samples.map { it.cadenceSpm }, r.intervalSec, 40.0, SlowColor, isDark)
            Text("速度 km/h", fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 10.dp))
            MetricChart(r.samples.map { it.speedMps * 3.6 }, r.intervalSec, 3.0, AccentBlue, isDark)
        } else {
            Text("暂无步频数据", fontSize = 11.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 10.dp))
        }
        Text(
            "平均步频 %.0f · 最高 %.0f · 最低 %.0f 步/分 · 平均步幅 %.2f m".format(
                s.avgCadenceSpm, s.maxCadenceSpm, s.minCadenceSpm, s.strideM
            ),
            fontSize = 10.sp, color = AppColors.textSecondary(isDark), modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** 乐跑用绿色标配速，越慢越浅；这里按与最慢一公里的比例给同色系。 */
private fun splitColor(pace: Double, worst: Double): Color {
    val k = if (worst <= 0.0) 0.5 else (pace / worst).coerceIn(0.0, 1.0)
    return Color(
        red = (0.12f + 0.05f * k.toFloat()),
        green = (0.66f - 0.16f * k.toFloat()),
        blue = (0.42f - 0.10f * k.toFloat())
    )
}

@Composable
private fun MetricChart(
    values: List<Double>, intervalSec: Int, axisStep: Double, lineColor: Color, isDark: Boolean
) {
    val sub = AppColors.textSecondary(isDark)
    val subArgb = sub.toArgb()
    val maxV = values.maxOrNull() ?: 0.0
    val top = ceil(max(maxV, axisStep) / axisStep) * axisStep
    val totalMin = values.size * intervalSec / 60.0
    Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 6.dp)) {
        val left = 34.dp.toPx()
        val bottom = 22.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val topPad = 4.dp.toPx()
        val w = (right - left).coerceAtLeast(1f)
        val h = (size.height - bottom - topPad).coerceAtLeast(1f)
        val label = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            color = subArgb
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val grid = sub.copy(alpha = 0.15f)
        for (i in 0..3) {
            val v = top * i / 3
            val y = size.height - bottom - (h * (v / top)).toFloat()
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("%.0f".format(v), left - 4.dp.toPx(), y + 3, label)
        }
        fun x(i: Int) = left + w * i / (values.size - 1).coerceAtLeast(1)
        fun y(v: Double) = size.height - bottom - (h * (v / top)).toFloat()

        val line = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val px = x(i)
            val py = y(v)
            if (i == 0) { line.moveTo(px, py); fill.moveTo(px, py) } else { line.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo(x(values.lastIndex), size.height - bottom)
        fill.lineTo(x(0), size.height - bottom)
        fill.close()
        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.55f), lineColor.copy(alpha = 0.04f)),
                startY = topPad, endY = size.height - bottom
            )
        )
        drawPath(line, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        val xLabel = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9.sp.toPx()
            color = subArgb
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val step = if (totalMin <= 10) 2 else (totalMin / 5).toInt().coerceAtLeast(1)
        var m = 0
        while (m <= totalMin + 0.01) {
            val i = ((m * 60) / intervalSec).toInt()
            if (i in values.indices) {
                drawContext.canvas.nativeCanvas.drawText(
                    m.toString(), x(i), size.height - 6.dp.toPx(), xLabel
                )
            }
            m += step
        }
        // 单位标在绘图区右上角，避开与最右侧分钟数重叠
        drawContext.canvas.nativeCanvas.drawText(
            "时间(分钟)", right, topPad + 9.sp.toPx(), xLabel.apply { textAlign = android.graphics.Paint.Align.RIGHT }
        )
    }
}
