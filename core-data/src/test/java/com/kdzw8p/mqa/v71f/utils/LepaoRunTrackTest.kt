package com.kdzw8p.mqa.v71f.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用可手算的合成记录锁住 LepaoRunTrack 的换算口径：分公里配速、不足一公里的归一化、
 * 打卡点序号标签，以及色带是否按速度真正分段。轨迹点只有经纬度，时间全部来自 interval。
 */
class LepaoRunTrackTest {

    private fun detail(
        distanceKm: Double, timeSec: Int, stepNum: Int, steps: List<Int>,
        interval: Int = 60, checkpoints: List<Pair<Double, Double>> = emptyList(),
        track: List<Pair<Double, Double>> = emptyList()
    ): String {
        fun arr(points: List<Pair<Double, Double>>): JSONArray = JSONArray().apply {
            points.forEachIndexed { i, p ->
                put(JSONObject().put("latitude", p.first).put("longtitude", p.second).put("point_index", i + 1))
            }
        }
        val body = JSONObject()
            .put("distance", distanceKm)
            .put("time", timeSec)
            .put("step_num", stepNum)
            .put("run_zone_name", "测试场地")
            .put("start_time", 1761186480L)
            .put("step_info", JSONObject().put("interval", interval)
                .put("list", JSONArray().apply { steps.forEach { put(it) } }))
        if (checkpoints.isNotEmpty()) body.put("point_list", arr(checkpoints))
        if (track.isNotEmpty()) body.put("point_list_system", arr(track))
        return JSONObject().put("detail", body).toString()
    }

    /** 10 分钟匀速 1 公里：每分钟 100 步，配速必须是 10'00"，且只产生一整公里。 */
    @Test
    fun `even one kilometre yields single split with exact pace`() {
        val track = (0..10).map { 24.0 + it * 0.0009 to 120.0 }
        val r = LepaoRunTrack.parse(detail(1.0, 600, 1000, List(10) { 100 }, track = track))!!
        assertEquals(1, r.splits.size)
        assertEquals(1, r.splits[0].km)
        assertTrue(!r.splits[0].partial)
        assertEquals(600.0, r.splits[0].paceSecPerKm, 0.001)
        assertEquals(600.0, r.summary.avgPaceSecPerKm, 0.001)
        assertEquals("10'00\"", LepaoRunTrack.formatPace(r.summary.avgPaceSecPerKm))
        assertEquals(1000, r.summary.stepNum)
        assertEquals(100.0, r.samples.first().cadenceSpm, 0.001)
        assertEquals(1.0, r.summary.strideM, 0.001)
    }

    /** 1.5 公里不足整公里收尾：第二段按实际距离归一到每公里配速，并标记 partial。 */
    @Test
    fun `partial kilometre normalises pace and is flagged`() {
        val track = (0..10).map { 24.0 + it * 0.0013 to 120.0 }
        val r = LepaoRunTrack.parse(detail(1.5, 600, 400, List(10) { 40 }, track = track))!!
        assertEquals(2, r.splits.size)
        assertTrue(r.splits[1].partial)
        assertEquals(400.0, r.splits[0].paceSecPerKm, 0.05)
        assertEquals(400.0, r.splits[1].paceSecPerKm, 0.05)
        assertEquals(500.0, r.splits[1].distanceM, 0.5)
        assertEquals(400.0, r.summary.fastestPaceSecPerKm, 0.05)
    }

    /** 打卡点从 1 编号，始/终取轨迹首尾，与乐跑一致。 */
    @Test
    fun `checkpoints get start end and ordinal labels`() {
        val cps = listOf(24.0 to 120.0, 24.0 to 120.001, 24.0 to 120.002, 24.0 to 120.003)
        val r = LepaoRunTrack.parse(detail(1.0, 600, 1000, List(10) { 100 }, checkpoints = cps))!!
        assertEquals(listOf("始", "1", "2", "3", "4", "终"), r.checkpoints.map { it.label })
        assertEquals(4, r.checkInCount)
    }

    /** 详情与列表都只有打卡点时，轨迹文件（明文 JSON 数组）必须成为主路径。 */
    @Test
    fun `track file becomes the main path`() {
        val cps = listOf(24.0 to 120.0)
        val arr = (0..40).joinToString(",", "[", "]") {
            "{\"a\":${24.0 + it * 0.0001},\"o\":${120.0 + it * 0.0002}}"
        }
        val r = LepaoRunTrack.parse(
            detail(1.0, 600, 1000, List(10) { 100 }, checkpoints = cps), "", arr
        )!!
        assertEquals("record_file", r.trackSource)
        assertEquals(41, r.track.size)
        assertEquals(41, r.track.distinctBy { it.lat to it.lng }.size)
    }

    /** 点位自带速度时，配速与色带应改用真实速度，而不是步数分摊。 */
    @Test
    fun `per point speed drives splits when present`() {
        val arr = (0..40).joinToString(",", "[", "]") {
            "{\"a\":${24.0 + it * 0.0003},\"o\":120.0,\"s\":${if (it < 20) 2.0 else 4.0}}"
        }
        val r = LepaoRunTrack.parse(
            detail(1.0, 600, 1000, List(10) { 100 }, checkpoints = listOf(24.0 to 120.0)), "", arr
        )!!
        assertTrue(r.speedFromTrack)
        assertTrue("splits=${r.splits.size}", r.splits.size >= 2)
        assertTrue(r.summary.fastestPaceSecPerKm < r.summary.slowestPaceSecPerKm)
    }

    /** 详情只给打卡点时，列表项里的 log_points 字符串应该胜出成为主轨迹。 */
    @Test
    fun `dense log_points from list record wins over checkpoints`() {
        val cps = listOf(24.0 to 120.0, 24.0 to 120.01, 24.0 to 120.02)
        val logPts = (0..30).joinToString(",") {
            "{\"latitude\":${24.0 + it * 0.0002},\"longtitude\":${120.0 + it * 0.0003}}"
        }
        val list = JSONObject().put("log_points", "[$logPts]").toString()
        val r = LepaoRunTrack.parse(detail(1.0, 600, 1000, List(10) { 100 }, checkpoints = cps), list)!!
        assertEquals("log_points", r.trackSource)
        assertEquals(31, r.track.size)
        assertEquals("始", r.checkpoints.first().label)
        assertEquals("终", r.checkpoints.last().label)
        assertEquals(3, r.checkInCount)
    }

    /** 步频忽快忽慢时色带必须真的分段，而不是整条同色。 */
    @Test
    fun `varying cadence produces multiple coloured bands`() {
        val track = (0..40).map { 24.0 + it * 0.0002 to 120.0 + it * 0.0001 }
        val r = LepaoRunTrack.parse(detail(0.5, 300, 300, listOf(10, 80, 10, 80, 120), track = track))!!
        assertTrue("bands=${r.bands.size}", r.bands.size >= 3)
        assertTrue(r.bands.map { it.color }.distinct().size >= 2)
        assertEquals(5, r.samples.size)
    }

    /** 没有 step_info 时不能崩，也不能把 0 当成有效配速。 */
    @Test
    fun `missing step info degrades without crashing`() {
        val body = JSONObject().put("distance", 1.2).put("time", 500).put("step_num", 0)
            .put("point_list", JSONArray().put(JSONObject().put("latitude", 24.0).put("longtitude", 120.0)))
        val r = LepaoRunTrack.parse(JSONObject().put("detail", body).toString())!!
        assertTrue(r.samples.isEmpty())
        assertTrue(r.splits.isEmpty())
        assertEquals(0.0, r.summary.avgCadenceSpm, 0.001)
        assertEquals("--", LepaoRunTrack.formatPace(0.0))
    }
}
