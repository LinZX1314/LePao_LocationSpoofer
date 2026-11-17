package com.kdzw8p.mqa.v71f.utils

/** 输入边界统一校验：允许小数逗号，不把非法值截断/夹紧后冒充用户输入。 */
object NumericSettingInput {
    /** 显示和提交使用相同精度，不把界面看不到的小数写入实际设置。 */
    fun normalize(value: Float, min: Float, max: Float, decimals: Int): Float? {
        if (!value.isFinite() || !min.isFinite() || !max.isFinite() || min > max ||
            value !in min..max || decimals !in 0..3) return null
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = (kotlin.math.round(value.toDouble() * factor) / factor).toFloat()
        return rounded.takeIf { it in min..max }
    }

    fun parse(text: String, min: Float, max: Float, whole: Boolean): Float? {
        if (!min.isFinite() || !max.isFinite() || min > max) return null
        val normalized = text.trim().replace(',', '.')
        if (!normalized.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
        val value = normalized.toFloatOrNull()?.takeIf { it.isFinite() && it in min..max } ?: return null
        if (whole && value % 1f != 0f) return null
        return value
    }
}
