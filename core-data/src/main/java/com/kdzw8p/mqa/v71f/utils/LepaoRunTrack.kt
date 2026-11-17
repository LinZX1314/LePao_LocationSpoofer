package com.kdzw8p.mqa.v71f.utils

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Run/recordDetailV270 的只读解析：把点位还原成乐跑那样的轨迹、分公里配速和步频曲线。
 *
 * 服务端不给逐点时间，只有按 interval 采样的步数表，所以「每分钟走了多少距离」按该分钟步数占比
 * 分摊总里程：色带和分公里配速都由这个剖面推出。步数、步频、总里程、总用时是服务端真实值。
 */
object LepaoRunTrack {

    data class Pt(val lat: Double, val lng: Double, val index: Int = 0, val label: String = "", val speed: Double = 0.0)

    /** 同色相连的一段折线，一次 addPolyline 画完，避免逐点调用。 */
    data class Band(val points: List<Pair<Double, Double>>, val color: Int)

    data class Sample(
        val minute: Int, val steps: Int, val cadenceSpm: Double, val distanceM: Double,
        val cumM: Double, val speedMps: Double, val paceSecPerKm: Double
    )

    data class Split(
        val km: Int, val distanceM: Double, val timeSec: Double,
        val paceSecPerKm: Double, val partial: Boolean
    )

    data class Summary(
        val distanceKm: Double, val timeSec: Int, val stepNum: Int,
        val avgPaceSecPerKm: Double, val avgSpeedKmh: Double,
        val avgCadenceSpm: Double, val maxCadenceSpm: Double, val minCadenceSpm: Double,
        val strideM: Double, val fastestPaceSecPerKm: Double, val slowestPaceSecPerKm: Double
    )

    data class Result(
        val track: List<Pt>, val checkpoints: List<Pt>, val bands: List<Band>,
        val samples: List<Sample>, val splits: List<Split>, val summary: Summary,
        val intervalSec: Int, val title: String, val startTimeSec: Long,
        val status: String, val failedReason: String, val checkInCount: Int,
        val trackSource: String = "打卡点",
        /** 响应里所有能当轨迹的数组及其点数，用于核对服务端到底给了什么。 */
        val candidates: List<Pair<String, Int>> = emptyList(),
        /** 响应的顶层字段及其体量，轨迹缺失时用来判断到底返了什么。 */
        val fields: List<String> = emptyList(),
        /** 轨迹文件情况：是否拿到、多少字节、解出多少点。 */
        val fileInfo: String = "",
        /** 是否有逐点速度：true 时配速与色带来自记录本身，不再是按步数分摊的估算。 */
        val speedFromTrack: Boolean = false
    )

    private data class Raw(val lat: Double, val lng: Double, val index: Int, val speed: Double = 0.0)

    /**
     * 轨迹能落在 point_list / point_list_system / log_points / path / log_data 等任意字段，也可能是
     * 包在字符串里的 JSON。这里把所有候选都展开，取点数最多的一路；列表项的原始 JSON 作为兜底。
     */
    fun parse(rawJson: String, listJson: String = "", trackFileText: String = ""): Result? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val body = root.optJSONObject("detail") ?: root
        val listRoot = runCatching { JSONObject(listJson) }.getOrNull()
        val cands = candidatesOf(body, listRoot).toMutableList()
        val filePts = filePoints(trackFileText)
        if (filePts.size >= 2) cands += Cand("record_file", filePts)
        val best = cands.sortedWith(
            compareByDescending<Cand> { it.pts.size }
                .thenBy { PREFERRED.indexOf(it.name.lowercase()).let { i -> if (i < 0) 99 else i } }
        ).firstOrNull()
        val track = ordered(best?.pts.orEmpty()).map { Pt(it.lat, it.lng, speed = it.speed) }
        val checkpoints = checkpointsOf(body)
        val pins = ArrayList<Pt>()
        if (track.isNotEmpty()) pins += Pt(track.first().lat, track.first().lng, 0, "始")
        checkpoints.forEachIndexed { i, p -> pins += Pt(p.lat, p.lng, i + 1, (i + 1).toString()) }
        if (track.size > 1) pins += Pt(track.last().lat, track.last().lng, 0, "终")
        val totalKm = body.optDouble("distance", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val timeSec = body.optInt("time", 0)
        val stepNum = body.optInt("step_num", 0)
        val stepInfo = body.optJSONObject("step_info")
        val rawSteps = stepInfo?.optJSONArray("list")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        val interval = stepInfo?.optInt("interval", 0)?.takeIf { it > 0 }
            ?: if (rawSteps.isNotEmpty() && timeSec > 0) (timeSec / rawSteps.size).coerceAtLeast(1) else 60
        val cumRaw = cumulative(track)
        // 点位连线的几何长度会大于服务端里程（GPS 拖动），按服务端距离归一，分公里才会是 1 公里 + 不足一公里。
        val trackLen = cumRaw.lastOrNull() ?: 0.0
        val cum = if (trackLen > 1.0 && totalKm > 0.0) cumRaw.map { it * (totalKm * 1000.0) / trackLen } else cumRaw
        val totalM = if (totalKm > 0.0) totalKm * 1000.0 else trackLen
        // 步频按 interval 折算成步/分；里程与时间按真实总时长均摊各采样，使分公里配速之和等于总用时。
        val sampleSec = if (timeSec > 0 && rawSteps.isNotEmpty()) timeSec.toDouble() / rawSteps.size else interval.toDouble()
        val fromTrack = trackSamples(track, cum, interval, rawSteps, timeSec)
        val samples = fromTrack ?: samples(rawSteps, interval, sampleSec, totalM, stepNum)
        val splits = splits(samples, sampleSec)
        return Result(
            track = track,
            checkpoints = pins,
            bands = bands(track, cum, samples),
            samples = samples,
            splits = splits,
            summary = summarize(totalKm, timeSec, stepNum, samples, splits),
            intervalSec = interval,
            title = body.optString("run_zone_name"),
            startTimeSec = epochSec(body.opt("start_time")),
            status = body.optString("record_status"),
            failedReason = body.optString("record_failed_reason"),
            checkInCount = maxOf(body.optInt("points", 0), checkpoints.size),
            trackSource = best?.name ?: "打卡点",
            candidates = cands.sortedByDescending { it.pts.size }.take(6).map { it.name to it.pts.size },
            fileInfo = if (trackFileText.isBlank()) "file 无"
            else "file ${trackFileText.length}B" +
                if (filePts.size >= 2) " · ${filePts.size} 点" else " · 未解出点",
            speedFromTrack = fromTrack != null,
            fields = body.keys().asSequence().map { k ->
                val v = body.opt(k)
                val size = when (v) {
                    is JSONArray -> "${v.length()}"
                    is JSONObject -> "{}"
                    is String -> "s${v.length}"
                    else -> ""
                }
                if (size.isEmpty()) k else "$k[$size]"
            }.toList()
        )
    }

    /** 轨迹文件：明文 JSON，或是 AES 加密后的 Base64（用的正是接口同一套 key/iv）。 */
    private fun filePoints(text: String): List<Raw> {
        val t = text.trim()
        if (t.length < 8) return emptyList()
        val plain = if (t[0] == '[' || t[0] == '{') t else (LepaoApiCrypto.decrypt(t) ?: return emptyList())
        val p = plain.trimStart()
        return runCatching {
            if (p.startsWith("[")) pointsOf(JSONArray(p), 0) else pointsOf(JSONObject(p), 0)
        }.getOrDefault(emptyList())
    }

    private data class Cand(val name: String, val pts: List<Raw>)

    private fun candidatesOf(body: JSONObject, listRoot: JSONObject?): List<Cand> {
        val all = ArrayList<Cand>()
        fun scan(root: JSONObject?) {
            root ?: return
            root.keys().forEach { key ->
                val pts = pointsOf(root.opt(key), 0)
                if (pts.size >= 2) all += Cand(key, pts)
            }
        }
        scan(body)
        scan(listRoot)
        return all
    }

    /** 递归展开：点数组、以 id 为键的点对象、或被包成字符串的 JSON。 */
    private fun pointsOf(node: Any?, depth: Int): List<Raw> {
        if (node == null || node == JSONObject.NULL || depth > 2) return emptyList()
        return when (node) {
            is JSONArray -> (0 until node.length()).mapNotNull { rawPoint(node.opt(it)) }
            is JSONObject -> if (rawPoint(node) != null) emptyList()
            else node.keys().asSequence().flatMap { pointsOf(node.opt(it), depth + 1).asSequence() }.toList()
            is String -> {
                val t = node.trim()
                if (t.length < 8 || (t[0] != '[' && t[0] != '{')) emptyList()
                else runCatching { pointsOf(JSONArray(t), depth + 1) }
                    .getOrElse { runCatching { pointsOf(JSONObject(t), depth + 1) }.getOrDefault(emptyList()) }
            }
            else -> emptyList()
        }
    }

    /** 一个对象是不是轨迹点：坐标字段直接给数，或是「纬度,经度」的 jingwei 串。 */
    private fun rawPoint(node: Any?): Raw? {
        val o = node as? JSONObject ?: return null
        val lat = firstDouble(o, "latitude", "lat", "a")
        val lng = firstDouble(o, "longtitude", "longitude", "lng", "lon", "o")
        if (lat != null && lng != null) {
            if (abs(lat) > 90.0 || abs(lng) > 180.0) return null
            if (abs(lat) < 1e-6 && abs(lng) < 1e-6) return null
            return Raw(lat, lng, o.optInt("point_index", o.optInt("point_id", 0)), firstDouble(o, "s", "speed") ?: 0.0)
        }
        val jw = o.optString("jingwei", "")
        if (jw.contains(',')) {
            val parts = jw.split(',')
            val a = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val b = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (a != null && b != null && !(abs(a) < 1e-6 && abs(b) < 1e-6)) {
                return Raw(a, b, o.optInt("point_index", 0))
            }
        }
        return null
    }

    /** 打卡点：point_list 条目。点数过多说明这一路是全量日志而不是打卡点，避免满屏图钉。 */
    private fun checkpointsOf(body: JSONObject): List<Raw> {
        val list = pointsOf(body.optJSONArray("point_list"), 0)
        return if (list.size > 20) emptyList() else list
    }

    private fun raw(arr: JSONArray?): List<Raw> {
        if (arr == null) return emptyList()
        val out = ArrayList<Raw>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = firstDouble(o, "latitude", "lat") ?: continue
            val lng = firstDouble(o, "longtitude", "longitude", "lng", "lon") ?: continue
            if (abs(lat) < 1e-6 && abs(lng) < 1e-6) continue
            out += Raw(lat, lng, o.optInt("point_index", i + 1))
        }
        return out
    }

    private fun firstDouble(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) o.optString(k).toDoubleOrNull()?.let { return it }
        return null
    }

    /** 服务器下发顺序就是行进顺序；只有 point_index 严格递增时才按它排。 */
    private fun ordered(list: List<Raw>): List<Raw> =
        if (list.size > 1 && list.zipWithNext().all { it.second.index > it.first.index })
            list.sortedBy { it.index } else list

    private fun haversineM(a: Pt, b: Pt): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6371000.0 * atan2(sqrt(s), sqrt(1 - s))
    }

    private fun cumulative(track: List<Pt>): List<Double> {
        if (track.isEmpty()) return emptyList()
        val out = ArrayList<Double>(track.size)
        out += 0.0
        for (i in 1 until track.size) out += out[i - 1] + haversineM(track[i - 1], track[i])
        return out
    }

    /** 单调不减且末值接近 step_num 视为累计步数，否则视为每区间步数。 */
    /** 点位自带速度时（记录文件里的 s）用真实速度推时间轴，比按步数分摊更准。 */
    private fun trackSamples(
        track: List<Pt>, cum: List<Double>, interval: Int, steps: List<Int>, timeSec: Int
    ): List<Sample>? {
        if (track.size < 3) return null
        if (track.count { it.speed > 0.3 } < track.size * 0.6) return null
        val t = DoubleArray(track.size)
        for (i in 1 until track.size) {
            val d = cum[i] - cum[i - 1]
            t[i] = t[i - 1] + d / max(track[i].speed, 0.5)
        }
        val rawSpan = t.last()
        if (rawSpan <= 1.0) return null
        // 服务端的用时才是权威值，按比例缩放时间轴，使各公里配速之和等于总用时。
        val scale = if (timeSec > 0) timeSec / rawSpan else 1.0
        val bins = max(1, kotlin.math.ceil((rawSpan * scale) / interval).toInt())
        if (bins <= 0) return null
        val dist = DoubleArray(bins)
        for (i in 1 until track.size) {
            val d = cum[i] - cum[i - 1]
            if (d <= 0.0) continue
            var a = t[i - 1] * scale
            val b = t[i] * scale
            if (b <= a) continue
            while (a < b) {
                val bin = (a / interval).toInt().coerceIn(0, bins - 1)
                val edge = minOf(b, ((bin + 1) * interval).toDouble())
                dist[bin] += d * ((edge - a) / (b - a))
                a = edge
            }
        }
        var cumD = 0.0
        return dist.mapIndexed { i, d ->
            cumD += d
            val s = steps.getOrElse(i) { 0 }
            Sample(i, s, s * 60.0 / interval, d, cumD, d / interval, if (d > 0.05) interval / d * 1000.0 else 0.0)
        }
    }

    private fun samples(raw: List<Int>, interval: Int, sampleSec: Double, totalM: Double, stepNum: Int): List<Sample> {
        if (raw.isEmpty()) return emptyList()
        val cumulativeSteps = raw.size > 1 && raw.zipWithNext().all { it.second >= it.first } &&
            (stepNum <= 0 || abs(raw.last() - stepNum) <= stepNum * 0.1 + 5)
        val per = if (cumulativeSteps) raw.mapIndexed { i, v -> (v - if (i == 0) 0 else raw[i - 1]).coerceAtLeast(0) }
        else raw
        val sum = per.sum().coerceAtLeast(1)
        var cum = 0.0
        return per.mapIndexed { i, steps ->
            val d = totalM * steps / sum
            cum += d
            val speed = d / sampleSec
            Sample(i, steps, steps * 60.0 / interval, d, cum, speed, if (speed > 0.05) 1000.0 / speed else 0.0)
        }
    }

    private fun splits(samples: List<Sample>, sampleSec: Double): List<Split> {
        val out = ArrayList<Split>()
        var accD = 0.0
        var accT = 0.0
        var km = 1
        val step = sampleSec
        samples.forEach { s ->
            var d = s.distanceM
            var t = step
            while (accD + d >= 1000.0 && d > 0.0) {
                val need = 1000.0 - accD
                val part = t * (need / d)
                accT += part
                t -= part
                d -= need
                accD = 0.0
                out += Split(km, 1000.0, accT, accT, false)
                accT = 0.0
                km++
            }
            accD += d
            accT += t
        }
        if (accD > 20.0 && accT > 0.0) out += Split(km, accD, accT, accT / accD * 1000.0, true)
        return out
    }

    private fun summarize(km: Double, time: Int, steps: Int, s: List<Sample>, splits: List<Split>): Summary {
        val cad = s.filter { it.steps > 0 }.map { it.cadenceSpm }
        val paces = splits.map { it.paceSecPerKm }.filter { it > 0 }
        return Summary(
            distanceKm = if (km > 0) km else (s.lastOrNull()?.cumM ?: 0.0) / 1000.0,
            timeSec = time,
            stepNum = steps,
            avgPaceSecPerKm = if (km > 0 && time > 0) time / km else 0.0,
            avgSpeedKmh = if (time > 0 && km > 0) km / time * 3600.0 else 0.0,
            avgCadenceSpm = if (time > 0) steps * 60.0 / time else cad.average().takeIf { !it.isNaN() } ?: 0.0,
            maxCadenceSpm = cad.maxOrNull() ?: 0.0,
            minCadenceSpm = cad.minOrNull() ?: 0.0,
            strideM = if (steps > 0 && km > 0) km * 1000.0 / steps else 0.0,
            fastestPaceSecPerKm = paces.minOrNull() ?: 0.0,
            slowestPaceSecPerKm = paces.maxOrNull() ?: 0.0
        )
    }

    /** 逐段按「该段位于第几分钟 + 那一分钟的速度」着色，同色相邻段合并成一条折线。 */
    private fun bands(track: List<Pt>, cum: List<Double>, samples: List<Sample>): List<Band> {
        if (track.size < 2) return emptyList()
        if (samples.isEmpty()) {
            return listOf(Band(track.map { it.lat to it.lng }, SLOW_COLOR))
        }
        val speeds = samples.map { it.speedMps }
        val lo = speeds.minOrNull() ?: 0.0
        val hi = speeds.maxOrNull() ?: 0.0
        val colors = (0 until track.size - 1).map { i ->
            val mid = (cum[i] + cum[i + 1]) / 2
            val speed = samples.firstOrNull { it.cumM >= mid }?.speedMps ?: speeds.last()
            val t = if (hi - lo < 1e-6) 0.5 else (speed - lo) / (hi - lo)
            heatColor(t)
        }
        val out = ArrayList<Band>()
        var start = 0
        for (i in 1..colors.size) {
            if (i == colors.size || colors[i] != colors[start]) {
                val pts = (start..i).map { track[it].lat to track[it].lng }
                if (pts.size >= 2) out += Band(pts, colors[start])
                start = i
            }
        }
        return out
    }

    /** 慢=绿、中=黄、快=红，与乐跑的「慢—快」色带一致。 */
    private fun heatColor(t: Double): Int {
        val c = t.coerceIn(0.0, 1.0)
        val (r, g, b) = if (c < 0.5) {
            val k = c / 0.5
            Triple(lerp(0x1E, 0xFF, k), lerp(0x9E, 0xD0, k), lerp(0x60, 0x28, k))
        } else {
            val k = (c - 0.5) / 0.5
            Triple(lerp(0xFF, 0xF0, k), lerp(0xD0, 0x44, k), lerp(0x28, 0x38, k))
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, k: Double): Int = (a + (b - a) * k).toInt().coerceIn(0, 255)

    private fun epochSec(value: Any?): Long {
        val n = when (value) {
            is Number -> value.toLong()
            else -> value?.toString()?.toLongOrNull() ?: return 0L
        }
        return when {
            n > 100_000_000_000L -> n / 1000L
            n > 1_500_000_000L -> n
            else -> 0L
        }
    }

    fun formatDate(sec: Long): String =
        if (sec <= 0L) "" else SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(sec * 1000L))

    fun formatPace(secPerKm: Double): String {
        if (secPerKm <= 0.0 || !secPerKm.isFinite() || secPerKm > 3600) return "--"
        val total = secPerKm.toInt()
        return "%d'%02d\"".format(total / 60, total % 60)
    }

    fun formatDuration(sec: Int): String =
        if (sec <= 0) "--" else "%02d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)

    private val SLOW_COLOR: Int = 0xFF1E9E60.toInt()

    /** 点数相同时的优先级：观感上 point_list 是乐跑的主轨迹。 */
    private val PREFERRED = listOf("point_list", "point_list_system", "log_points", "logpoints", "log_data", "path")
}
