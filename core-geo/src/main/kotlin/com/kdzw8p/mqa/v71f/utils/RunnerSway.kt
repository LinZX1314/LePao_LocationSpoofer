package com.kdzw8p.mqa.v71f.utils

import kotlin.math.PI
import kotlin.math.atan2

/**
 * 跑步轨迹的人性化摆动：真人跑步不会贴着一条几何线走，每隔几秒会自然偏离中线
 * 零点几米，方向角随之小幅摆动；再叠加注入层的 GPS 随机漂移，就是"跑者轨迹 + 真实定位误差"两层。
 *
 * 全部由 (startTimestamp, elapsedSec) 确定性生成——App 端、system_server 与目标进程
 * 对同一时刻算出同一偏移，各曲线不会互相漂移；速度为 0（到点 / 静止）时输出恒为 0，
 * 摆动幅度随速度缩放：慢走摆得小，快跑摆得大。
 */
object RunnerSway {

    data class Sway(val lateralM: Double, val bearingOffsetDeg: Double)

    /**
     * @param lateralM 沿路线法线的横向偏移（米，正负为左右两侧）
     * @param bearingOffsetDeg 方位角抖动（度）：由横向偏移的差分速度与前进速度求角得出，
     *   与位置摆动物理一致，而不是独立随机数
     * @param amplitudeScale 摆动幅度倍率：1.0 = 默认人味，0 = 完全贴线，>1 更明显地偏离中线
     */
    fun at(
        startTimestamp: Long,
        speedMs: Double,
        elapsedSec: Double,
        amplitudeScale: Double = 1.0
    ): Sway {
        if (speedMs <= 0.05 || amplitudeScale <= 0.0) return Sway(0.0, 0.0)
        val scale = (speedMs / 3.0).coerceIn(0.4, 1.6) * amplitudeScale
        val slow = MotionRealism.SmoothNoise(startTimestamp xor 0x51C7, 6.0, 14.0)
        val fast = MotionRealism.SmoothNoise(startTimestamp xor 0x2E9B, 1.6, 3.2)
        val lateral = scale * (0.85 * slow.value(elapsedSec) + 0.22 * fast.value(elapsedSec))
        val dt = 0.5
        val lateralNext = scale * (0.85 * slow.value(elapsedSec + dt) + 0.22 * fast.value(elapsedSec + dt))
        val lateralSpeed = (lateralNext - lateral) / dt
        val bearingOffset = Math.toDegrees(atan2(lateralSpeed, speedMs)).coerceIn(-8.0, 8.0)
        return Sway(lateral, bearingOffset)
    }
}
