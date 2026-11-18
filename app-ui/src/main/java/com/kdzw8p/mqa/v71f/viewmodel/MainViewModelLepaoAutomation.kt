package com.kdzw8p.mqa.v71f.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LEPAO_PKG = "com.lptiyu.tanke"
private const val ACTION_RUN_START = "com.kdzw8p.mqa.v71f.AUTO_RUN_START"
private const val ACTION_RUN_DONE = "com.kdzw8p.mqa.v71f.AUTO_RUN_DONE"
private const val ACTION_RUN_STATUS = "com.kdzw8p.mqa.v71f.RUN_STATUS"

private var lepaoAutomationJob: Job? = null

/** 单个账号在一键/批量跑里的阶段，给状态窗展示。 */
data class LepaoAutomationItem(val name: String, val phase: String)

data class LepaoAutomationState(
    val total: Int,
    val index: Int,
    val account: String,
    val phase: String,
    val km: Double,
    val items: List<LepaoAutomationItem>,
    val finished: Boolean = false
)

private val _lepaoAutomationState = MutableStateFlow<LepaoAutomationState?>(null)
val lepaoAutomationState: StateFlow<LepaoAutomationState?> = _lepaoAutomationState.asStateFlow()

fun dismissLepaoAutomation() {
    _lepaoAutomationState.value = null
}

fun cancelLepaoAutomation() {
    lepaoAutomationJob?.cancel()
    lepaoAutomationJob = null
    _lepaoAutomationState.value = _lepaoAutomationState.value?.copy(finished = true, phase = "已取消")
}

/**
 * 一键跑的轨迹来源：当前画布路线 > 路线库最新一条 > 打卡点环线 > 当前位置的直线兜底。
 * 打卡点环线放在路线库后面：用户存了自己跑过的路线时应该先跑那条，环线只当兜底。
 * 返回用了哪条，给状态窗显示，避免“明明有路线库却说没选路线”。
 */
private fun MainViewModel.ensureRunRoute(km: Double, account: String = ""): String {
    if (_uiState.value.routePoints.size >= 2) return "当前路线"
    val saved = _uiState.value.savedRoutes.firstOrNull { it.points.size >= 2 }
    if (saved != null) {
        _uiState.update {
            it.copy(
                routePoints = saved.points,
                routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
            )
        }
        return saved.name
    }
    val lastRoute = parseRoutePoints(settingsRepository.lepaoLastRouteJson)
    if (lastRoute.size >= 2) {
        _uiState.update {
            it.copy(
                routePoints = lastRoute,
                routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
            )
        }
        return "上次路线"
    }
    checkpointLoopRoute(account, km)?.let { return it }
    val s = _uiState.value
    val lat = s.latitudeInput.toDoubleOrNull() ?: return "无路线"
    val lng = s.longitudeInput.toDoubleOrNull() ?: return "无路线"
    val cosLat = kotlin.math.cos(Math.toRadians(lat)).let { if (it > 0.01) it else 0.01 }
    val dLng = km * 1.2 / (111.32 * cosLat)
    _uiState.update {
        it.copy(routePoints = listOf(
            com.kdzw8p.mqa.v71f.data.model.RoutePoint(lat, lng),
            com.kdzw8p.mqa.v71f.data.model.RoutePoint(lat, lng + dLng)
        ))
    }
    return "自动直线"
}

private fun parseRoutePoints(json: String): List<com.kdzw8p.mqa.v71f.data.model.RoutePoint> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(com.kdzw8p.mqa.v71f.data.model.RoutePoint(o.optDouble("lat"), o.optDouble("lng")))
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * 定向打卡跑区要求按顺序经过打卡点才算打卡（乐跑自述「经过时系统会自动打卡」，判定半径
 * 就是 run_line_info.log_max_distance，实测 30 米）。路线库里的历史轨迹是绕操场跑的，
 * 常常擦着判定圈边缘过去，于是出现「打卡次数过少，未达到合格标准」。
 *
 * 这里直接用 beforeRunV260 下发的 run_line_info.point_list 生成一条闭合环线：
 * 按 index 串起所有打卡点，重复绕圈直到里程覆盖目标，末段按比例截断。
 */
private fun MainViewModel.checkpointLoopRoute(account: String, km: Double): String? {
    if (account.isBlank() || km <= 0.0) return null
    val rule = runCatching {
        settingsRepository.getLepaoProfile(account)?.optString("run_rule_json").orEmpty()
    }.getOrNull().orEmpty()
    if (rule.isBlank()) return null
    val lineInfo = runCatching {
        org.json.JSONObject(rule).optJSONObject("run_line_info")
    }.getOrNull() ?: return null
    val arr = lineInfo.optJSONArray("point_list") ?: return null

    data class Cp(val idx: Int, val lat: Double, val lng: Double)
    val raw = ArrayList<Cp>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val jw = o.optString("jingwei").split(",")
        if (jw.size != 2) continue
        val lat = jw[0].trim().toDoubleOrNull() ?: continue
        val lng = jw[1].trim().toDoubleOrNull() ?: continue
        if (lat == 0.0 && lng == 0.0) continue
        raw.add(Cp(o.optInt("index", i), lat, lng))
    }
    if (raw.size < 2) return null

    // 打卡点按 index 直连会自交（四个点常横跨操场），轨迹就成了星形。
    // 先按绕质心的方位角排序，再用闭合 Catmull-Rom 插值：曲线经过每个打卡点、不自交，
    // 看起来就是绕场地跑的正常环线；点几乎共线时退化成体育场形（两平行边 + 两个半圆）。
    val control = raw.sortedBy { it.idx }
        .distinctBy { "%.6f,%.6f".format(it.lat, it.lng) }
        .map { RingPoint(it.lat, it.lng) }
    if (control.size < 2) return null
    val ordered = ringOrder(control)
    val loop = smoothClosedRing(ordered)
    if (loop.size < 3) return null
    val ring = loop + loop.first()
    val segs = ring.zipWithNext().map { (a, b) -> metersBetween(a.lat, a.lng, b.lat, b.lng) }
    val perimeter = segs.sum()
    if (perimeter < 40.0) return null

    val wanted = km * 1000.0
    val out = ArrayList<com.kdzw8p.mqa.v71f.data.model.RoutePoint>()
    var acc = 0.0
    var laps = 0
    while (acc < wanted && laps < 500) {
        laps++
        var cut = false
        for (k in 0 until ring.size - 1) {
            val a = ring[k]
            val b = ring[k + 1]
            val seg = segs[k]
            if (acc + seg >= wanted) {
                val t = ((wanted - acc) / seg).coerceIn(0.0, 1.0)
                out.add(
                    com.kdzw8p.mqa.v71f.data.model.RoutePoint(
                        a.lat + (b.lat - a.lat) * t,
                        a.lng + (b.lng - a.lng) * t
                    )
                )
                acc = wanted
                cut = true
                break
            }
            out.add(com.kdzw8p.mqa.v71f.data.model.RoutePoint(a.lat, a.lng))
            acc += seg
        }
        if (cut) break
    }
    if (out.size < 2) return null
    _uiState.update {
        it.copy(
            routePoints = out,
            routePlanStage = com.kdzw8p.mqa.v71f.data.model.RoutePlanStage.READY
        )
    }
    val per = String.format(java.util.Locale.US, "%.0f", perimeter)
    return "打卡点环线(${ordered.size}点·${per}m/圈)"
}

private data class RingPoint(val lat: Double, val lng: Double)

/**
 * 把打卡点排成一条不自交的闭合顺序：绕质心按方位角排序。
 * 点几乎落在一条直线上时（操场两侧的打卡点很常见），方位角排序会退化成来回跑，
 * 这时改成体育场形：两端点 + 垂直方向各偏 15 米。
 */
private fun ringOrder(points: List<RingPoint>): List<RingPoint> {
    if (points.size == 2) return stadiumRing(points[0], points[1], 30.0)
    if (points.size < 3) return points
    val cy = points.sumOf { it.lat } / points.size
    val cx = points.sumOf { it.lng } / points.size
    val sorted = points.sortedBy { Math.atan2(it.lat - cy, it.lng - cx) }
    // 共线检测：最远两点连线，其余点到它的最大垂距太小就不当多边形用。
    var fa = sorted[0]
    var fb = sorted[1]
    var best = -1.0
    for (i in sorted.indices) for (j in i + 1 until sorted.size) {
        val d = metersBetween(sorted[i].lat, sorted[i].lng, sorted[j].lat, sorted[j].lng)
        if (d > best) { best = d; fa = sorted[i]; fb = sorted[j] }
    }
    val maxOffset = sorted.maxOf { pointToSegmentMeters(it, fa, fb) }
    return if (best < 40.0 || maxOffset < 3.0) stadiumRing(fa, fb, 30.0) else sorted
}

/** 到线段的距离（米）；用作共线判定。 */
private fun pointToSegmentMeters(p: RingPoint, a: RingPoint, b: RingPoint): Double {
    val cosLat = kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2)).coerceAtLeast(0.01)
    val px = (p.lng - a.lng) * 111320.0 * cosLat
    val py = (p.lat - a.lat) * 111320.0
    val bx = (b.lng - a.lng) * 111320.0 * cosLat
    val by = (b.lat - a.lat) * 111320.0
    val len2 = bx * bx + by * by
    if (len2 <= 1e-9) return kotlin.math.hypot(px, py)
    val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    return kotlin.math.hypot(px - bx * t, py - by * t)
}

/** 两端点 + 垂直方向各偏移半个宽度，形成一圈体育场形路线。 */
private fun stadiumRing(a: RingPoint, b: RingPoint, widthMeters: Double): List<RingPoint> {
    val midLat = (a.lat + b.lat) / 2
    val cosLat = kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
    val dx = (b.lng - a.lng) * 111320.0 * cosLat
    val dy = (b.lat - a.lat) * 111320.0
    val len = kotlin.math.hypot(dx, dy)
    if (len < 1e-6) return listOf(a, b)
    val half = widthMeters / 2
    val ox = -dy / len * half
    val oy = dx / len * half
    val dLat = oy / 111320.0
    val dLng = ox / (111320.0 * cosLat)
    return listOf(
        RingPoint(a.lat + dLat, a.lng + dLng),
        RingPoint(b.lat + dLat, b.lng + dLng),
        RingPoint(b.lat - dLat, b.lng - dLng),
        RingPoint(a.lat - dLat, a.lng - dLng)
    )
}

/** 闭合 Catmull-Rom：曲线经过每个控制点，转角自然圆滑，不会把打卡点裁掉。 */
private fun smoothClosedRing(points: List<RingPoint>, samplesPerSegment: Int = 6): List<RingPoint> {
    if (points.size < 3) return points
    val n = points.size
    val out = ArrayList<RingPoint>(n * samplesPerSegment)
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]
        val p1 = points[i]
        val p2 = points[(i + 1) % n]
        val p3 = points[(i + 2) % n]
        for (s in 0 until samplesPerSegment) {
            val t = s.toDouble() / samplesPerSegment
            val t2 = t * t
            val t3 = t2 * t
            fun v(a: Double, b: Double, c: Double, d: Double): Double = 0.5 * (
                2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3
                )
            out.add(
                RingPoint(
                    v(p0.lat, p1.lat, p2.lat, p3.lat),
                    v(p0.lng, p1.lng, p2.lng, p3.lng)
                )
            )
        }
    }
    return out
}

/** 球面距离（米）：打卡半径是米，路线长度也按米算，避免经纬度直接相加的误差。 */
private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * r * Math.asin(Math.sqrt(a))
}

private fun MainViewModel.bringHostToFront() {
    runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(LEPAO_PKG) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
    }
}

fun MainViewModel.getLepaoAutoStopKmSetting(): String = settingsRepository.lepaoAutoStopKm

fun MainViewModel.getLepaoRunKmMale(): String = settingsRepository.lepaoRunKmMale

fun MainViewModel.getLepaoRunKmFemale(): String = settingsRepository.lepaoRunKmFemale

fun MainViewModel.setLepaoRunKm(male: String, female: String) {
    settingsRepository.lepaoRunKmMale = male
    settingsRepository.lepaoRunKmFemale = female
}

fun MainViewModel.getLepaoMinPaceMale(): String = settingsRepository.lepaoMinPaceMaleSec

fun MainViewModel.getLepaoMinPaceFemale(): String = settingsRepository.lepaoMinPaceFemaleSec

fun MainViewModel.setLepaoMinPace(male: String, female: String) {
    settingsRepository.lepaoMinPaceMaleSec = male
    settingsRepository.lepaoMinPaceFemaleSec = female
}

/** 当前账号的最低平均配速（秒/公里）：按身份性别取男女各自的设置；0 = 不限制 */
internal fun MainViewModel.minPaceSecFor(name: String): Double {
    val identity = settingsRepository.getLepaoProfile(name)?.optString("lepao_identity_json").orEmpty()
    val sex = runCatching { org.json.JSONObject(identity).optString("sex") }.getOrDefault("")
    val raw = if (sex.contains("女")) settingsRepository.lepaoMinPaceFemaleSec
    else settingsRepository.lepaoMinPaceMaleSec
    return raw.toDoubleOrNull()?.coerceIn(0.0, 1200.0) ?: 0.0
}

/**
 * 按账号身份性别选目标公里：女用女性值、其余用男性值。
 * 参数 >0 时优先（用户在确认框里改过），否则读设置里的男女默认（女 1.5 / 男 1.8）。
 */
internal fun MainViewModel.targetKmFor(name: String, kmMale: Double, kmFemale: Double): Double {
    val male = if (kmMale > 0) kmMale
        else settingsRepository.lepaoRunKmMale.toDoubleOrNull() ?: 1.8
    val female = if (kmFemale > 0) kmFemale
        else settingsRepository.lepaoRunKmFemale.toDoubleOrNull() ?: 1.5
    val identity = settingsRepository.getLepaoProfile(name)?.optString("lepao_identity_json").orEmpty()
    val sex = runCatching { org.json.JSONObject(identity).optString("sex") }.getOrDefault("")
    return if (sex.contains("女")) female else male
}

/**
 * 一键跑/批量跑：切到目标账户 → 路线从起点重开 → 通知宿主自动进入跑步；
 * 宿主到点结束并上报后回传 AUTO_RUN_DONE，再处理下一个账户。
 * 状态通过 [lepaoAutomationState] 暴露给状态窗（进度条 + 每个账号阶段）。
 */
fun MainViewModel.runLepaoAutomation(names: List<String>, kmMale: Double = 0.0, kmFemale: Double = 0.0) {
    if (names.isEmpty()) return
    if (lepaoAutomationJob?.isActive == true) {
        Toast.makeText(context, "自动跑正在进行中", Toast.LENGTH_SHORT).show()
        return
    }
    if (kmMale <= 0.0 && kmFemale <= 0.0) {
        Toast.makeText(context, "目标公里要大于 0", Toast.LENGTH_LONG).show()
        return
    }
    // 路线优先用当前画布上的；没有就用路线库里最新一条；都没有才用直线兜底
    ensureRunRoute(maxOf(kmMale, kmFemale, 1.8))
    if (_uiState.value.routePoints.size < 2) {
        Toast.makeText(context, "先在路线页选一条路线，或在定位页放一个点", Toast.LENGTH_LONG).show()
        return
    }
    // 锁定本次批量用的路线：每个号结束会 stopRoutePlanning 清空画布，不锁住第二个号就掉回打卡点环线。
    val lockedRoute = _uiState.value.routePoints
    val phases = names.map { LepaoAutomationItem(it, "待跑") }.toMutableList()
    // 每个账号的目标公里不同（按性别），状态窗显示当前账号的那个
    var currentKm = 0.0
    fun publish(index: Int, account: String, phase: String, finished: Boolean = false) {
        _lepaoAutomationState.value = LepaoAutomationState(
            total = names.size, index = index, account = account, phase = phase,
            km = currentKm, items = phases.toList(), finished = finished
        )
    }
    publish(0, names.first(), "准备中")

    lepaoAutomationJob = viewModelScope.launch {
        for ((i, name) in names.withIndex()) {
            val targetKm = targetKmFor(name, kmMale, kmFemale)
            currentKm = targetKm
            phases[i] = LepaoAutomationItem(name, "切换账号 · 目标 ${String.format(java.util.Locale.US, "%.2f", targetKm)} km")
            publish(i, name, phases[i].phase)
            switchLepaoAccount(name)
            delay(6000)

            phases[i] = LepaoAutomationItem(name, "开始路线")
            publish(i, name, "开始路线")
            // 切号会写回该账号的坐标，这里再确认一次轨迹，并把用的哪条路线告知用户
            // 每个账号都从路线起点重新开始；循环由 startSimulationWithPoints 统一开启，
            // 不再把运行模式强改成 LOOP —— 摇杆（MANUAL）是默认，一键跑会在 1.5s 后自动替用户按开始。
            // 上一个号结束时 stopRoutePlanning 清空了画布，这里先放回锁定的那条路线，
            // 否则 ensureRunRoute 会以为没选路线、掉回打卡点环线。
            if (lockedRoute.size >= 2 && _uiState.value.routePoints.size < 2) {
                _uiState.update { it.copy(routePoints = lockedRoute) }
            }
            val routeName = ensureRunRoute(targetKm, name)
            phases[i] = LepaoAutomationItem(name, "路线：${routeName}（循环）")
            publish(i, name, "路线：${routeName}（循环）")
            startRouteFromBeginning()
            // 手动模式的路线是以暂停状态加载的（等悬浮摇杆按开始），一键跑要替用户按下，
            // 否则宿主在跑、位置却不动，里程永远停在 0.0x。
            delay(1500)
            motionController.resume()

            phases[i] = LepaoAutomationItem(name, "等待开跑")
            publish(i, name, "等待开跑")
            val done = CompletableDeferred<Boolean>()
            var lastStatusAt = System.currentTimeMillis()
            // 宿主每秒广播的真实跑步数据，状态窗直接展示
            var runKm = 0.0
            var runSpeed = 0.0
            var runPaused = false
            var runElapsed = ""
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_RUN_DONE -> done.complete(true)
                        ACTION_RUN_STATUS -> {
                            lastStatusAt = System.currentTimeMillis()
                            runKm = intent.getDoubleExtra("km", runKm)
                            runSpeed = intent.getDoubleExtra("speed", runSpeed)
                            runPaused = intent.getBooleanExtra("paused", runPaused)
                            runElapsed = intent.getStringExtra("elapsed").orEmpty()
                        }
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(ACTION_RUN_DONE)
                addAction(ACTION_RUN_STATUS)
            }, Context.RECEIVER_EXPORTED)
            try {
                // 宿主进程可能还在启动：重发几次，直到它回执或进入超时等待
                repeat(8) { pulse ->
                    if (done.isCompleted) return@repeat
                    // 自动化要点宿主界面，后台进程点不了控件；前两次脉冲时把宿主拉到最前
                    if (pulse < 2) bringHostToFront()
                    context.sendBroadcast(
                        Intent(ACTION_RUN_START).setPackage(LEPAO_PKG).putExtra("km", targetKm.toString())
                    )
                    delay(2500)
                }
                phases[i] = LepaoAutomationItem(name, "跑步中")
                publish(i, name, "跑步中")
                // 不做时长限制：只看公里。何时结束完全由宿主的里程判定负责，
                // 它结束并上传后会回传 AUTO_RUN_DONE；这里只负责保活宿主、实时展示进度。
                while (!done.isCompleted) {
                    delay(3000)
                    if (done.isCompleted) break
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAt > 12_000) {
                        bringHostToFront()
                        context.sendBroadcast(
                            Intent(ACTION_RUN_START).setPackage(LEPAO_PKG).putExtra("km", targetKm.toString())
                        )
                        lastStatusAt = now
                        val lost = "跑步中 · 宿主不在前台，正在拉回"
                        if (phases[i].phase != lost) {
                            phases[i] = LepaoAutomationItem(name, lost)
                            publish(i, name, lost)
                        }
                        continue
                    }
                    val detail = String.format(
                        java.util.Locale.US, "跑步中 %.2f/%.2f km · %.1f m/s%s%s",
                        runKm, targetKm, runSpeed,
                        if (runPaused) " · 已暂停" else "",
                        if (runElapsed.isNotBlank()) " · 用时 $runElapsed" else ""
                    )
                    if (phases[i].phase != detail) {
                        phases[i] = LepaoAutomationItem(name, detail)
                        publish(i, name, detail)
                    }
                }
                // 收到 AUTO_RUN_DONE：宿主已结束并上报
                phases[i] = LepaoAutomationItem(name, "完成")
                publish(i, name, "完成")
                // 定位要撑到乐跑上传完成：宿主是在确认后 12 秒才回执的，
                // 这里再留 2 秒缓冲才停模拟，避免上传途中的点落回真实位置。
                delay(2000)
                runCatching { stopRoutePlanning() }
                runCatching { locationRepository.setFloatingJoystickVisible(context, false) }
                runCatching { refreshLepaoProfileStats(name) }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
        publish(names.size, "", "全部完成", finished = true)
        // 单账号且确实跑完了才自动关窗
        if (names.size == 1 && _lepaoAutomationState.value?.items?.all { it.phase == "完成" } == true) {
            delay(2500)
            dismissLepaoAutomation()
        }
    }
}
