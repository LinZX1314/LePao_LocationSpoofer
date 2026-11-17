package com.kdzw8p.mqa.v71f.xposed.hooks

/** 使用运行系统的位布局，避免旧版/厂商 GnssStatus 将 SVID、星座和能力标志混读。 */
internal data class GnssStatusBitLayout(
    val svidShift: Int,
    val constellationShift: Int,
    val ephemerisFlag: Int,
    val almanacFlag: Int,
    val usedInFixFlag: Int,
    val carrierFlag: Int,
    val basebandFlag: Int
) {
    fun encode(svid: Int, constellation: Int, usedInFix: Boolean, carrier: Boolean, baseband: Boolean): Int {
        var flags = ephemerisFlag or almanacFlag
        if (usedInFix) flags = flags or usedInFixFlag
        if (carrier) flags = flags or carrierFlag
        if (baseband) flags = flags or basebandFlag
        return (svid shl svidShift) or (constellation shl constellationShift) or flags
    }
}
