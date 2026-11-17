package com.kdzw8p.mqa.v71f.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** 今日表示该日存在跑步记录，不代表成绩已经判定有效。 */
object LepaoTodayRun {
    data class Summary(val day: String, val count: Int, val invalidCount: Int = 0)

    fun recordDay(value: String, zone: ZoneId): LocalDate? {
        val text = value.trim()
        text.toLongOrNull()?.let { timestamp ->
            if (timestamp <= 0) return null
            return runCatching {
                (if (timestamp >= 100_000_000_000L) Instant.ofEpochMilli(timestamp)
                else Instant.ofEpochSecond(timestamp)).atZone(zone).toLocalDate()
            }.getOrNull()
        }
        return runCatching { Instant.parse(text).atZone(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    /** 不依赖列表排序；只有完整分页且日期可判定时才报告0次，重复页视为查询异常。 */
    internal fun collect(
        uid: String, now: Instant, zone: ZoneId, readPage: (Int, String) -> JSONObject
    ): Summary {
        require(LepaoRunData.isAccountId(uid))
        val today = now.atZone(zone).toLocalDate()
        val seen = hashSetOf<Long>()
        var count = 0
        // 当天有记录但 record_status != 1 的是无效成绩（里程过短/异常），单独计数
        var invalidCount = 0
        var number = 1
        while (true) {
            val data = readPage(number, today.year.toString())
            val list = data.optJSONArray("list") ?: data.optJSONArray("record_list") ?: error("Missing history list")
            if (list.length() == 0) break
            var added = 0
            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: error("Invalid history record")
                val owner = row.optString("uid")
                require(owner.isBlank() || owner == uid) { "History identity mismatch" }
                val id = row.optLong("id", row.optLong("record_id"))
                require(id > 0) { "Missing history identity" }
                if (!seen.add(id)) continue
                added++
                val date = recordDay(row.optString("start_time"), zone) ?: error("Unknown history date")
                if (date == today) {
                    if (row.optString("record_status") == "1") count++ else invalidCount++
                }
            }
            check(added > 0) { "Repeated history page" }
            val total = data.optString("total_num").toIntOrNull()
            if (total != null && total >= 0 && seen.size >= total) break
            number++
        }
        return Summary(today.toString(), count, invalidCount)
    }

    fun fetch(sessionJson: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val uid = JSONObject(sessionJson).optString("user_id")
        return collect(uid, now, zone) { page, year ->
            LepaoReadClient.request("Run2/allRecordList", sessionJson, mapOf("page" to page.toString(), "year" to year))
        }
    }
}
