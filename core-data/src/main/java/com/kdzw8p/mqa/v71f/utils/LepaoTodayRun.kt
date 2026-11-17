package com.kdzw8p.mqa.v71f.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

    /** 不依赖列表排序；只有完整分页且日期可判定时才报告0次，重复页视为查询异常。
     *  例外：回包自己证明是时间倒序、且已经越过今天时，后面页只可能更旧，可提前结束。 */
    internal fun collect(
        uid: String, now: Instant, zone: ZoneId, readPage: (Int, String) -> JSONObject
    ): Summary {
        require(LepaoRunData.isAccountId(uid))
        val today = now.atZone(zone).toLocalDate()
        val seen = hashSetOf<Long>()
        var count = 0
        // 当天有记录但 record_status != 1 的是无效成绩（里程过短/异常），单独计数
        var invalidCount = 0
        // 降序自证：一旦出现比前一条更新的记录就不再信任提前结束。
        var previousDate: LocalDate? = null
        var descending = true
        var sawBeforeToday = false
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
                val previous = previousDate
                if (previous != null && date > previous) descending = false
                previousDate = date
                if (date == today) {
                    if (row.optString("record_status") == "1") count++ else invalidCount++
                } else if (date < today) {
                    sawBeforeToday = true
                }
            }
            check(added > 0) { "Repeated history page" }
            val total = data.optString("total_num").toIntOrNull()
            // 乐跑自己的记录页就是开始时间倒序（最新在最上，见 11-17 / 11-15 / 11-12 那一列），
            // 所以第一页一旦跨过今天，今天跑没跑就已经确定，不再翻页。
            // 只有整页都是今天的记录（今天跑了不止一页）或回包出现升序时才继续。
            val pageHoldsAll = total != null && total in 0..list.length()
            val pageCoversToday = list.length() >= 2 || pageHoldsAll
            if (descending && sawBeforeToday && (count > 0 || pageCoversToday)) break
            if (total != null && total >= 0 && seen.size >= total) break
            number++
        }
        return Summary(today.toString(), count, invalidCount)
    }

    /** 单账号预取并发度：账号之间本身已并行，这里只控制同一年份分页的并发数。 */
    private const val PAGE_PREFETCH_CONCURRENCY = 8
    /** 记录极多的账号封顶，避免一次刷新打出上百个请求。 */
    private const val PAGE_PREFETCH_LIMIT = 40

    private val pagePool: java.util.concurrent.ExecutorService by lazy {
        Executors.newFixedThreadPool(PAGE_PREFETCH_CONCURRENCY) { runnable ->
            Thread(runnable, "lepao-today-page").apply { isDaemon = true }
        }
    }

    /**
     * 常见情况只要 1 次请求：倒序回包的第一页里一旦出现今天以前的记录，collect 就会提前结束。
     * 只有回包乱序、或整页全是今天的记录时，才在第一页之后把剩余页并发预取（不等首页）。
     */
    fun fetch(sessionJson: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val uid = JSONObject(sessionJson).optString("user_id")
        val year = now.atZone(zone).year.toString()
        val pages = ConcurrentHashMap<Int, Future<JSONObject>>()
        val prefetchStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun requestPage(page: Int): JSONObject =
            LepaoReadClient.request("Run2/allRecordList", sessionJson, mapOf("page" to page.toString(), "year" to year))

        val first = requestPage(1)
        val total = first.optString("total_num").toIntOrNull() ?: -1
        val pageSize = (first.optJSONArray("list") ?: first.optJSONArray("record_list"))?.length() ?: 0
        val lastPage = if (total > 0 && pageSize > 0) {
            ((total + pageSize - 1) / pageSize).coerceIn(1, PAGE_PREFETCH_LIMIT)
        } else 1

        fun startPrefetch() {
            if (!prefetchStarted.compareAndSet(false, true)) return
            for (page in 2..lastPage) {
                pages[page] = pagePool.submit(Callable { requestPage(page) })
            }
        }

        return collect(uid, now, zone) { page, _ ->
            if (page == 1) return@collect first
            startPrefetch()
            val future = pages[page] ?: return@collect requestPage(page)
            try {
                future.get()
            } catch (e: ExecutionException) {
                // 预取失败按原样抛给 collect，让失败语义和串行版本一致。
                throw e.cause ?: e
            }
        }
    }
}
