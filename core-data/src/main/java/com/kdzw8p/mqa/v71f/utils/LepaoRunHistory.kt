package com.kdzw8p.mqa.v71f.utils

import java.util.Calendar
import org.json.JSONObject

/** Run2/allRecordList 与 Run/recordDetailV270 均为只读查询，不触发跑步或申诉。 */
object LepaoRunHistory {

    data class Record(
        val name: String, val distance: String, val pace: String, val startTime: String,
        val usedTime: String, val recordId: Long, val ownerId: String = "",
        val year: String = "", val rawJson: String = "",
        /** 服务端 record_status：1=有效成绩，其余（3 等）为无效，不计入已跑次数 */
        val recordStatus: String = "", val errorType: String = ""
    ) {
        val valid: Boolean get() = recordStatus == "1"
    }
    data class Page(
        val date: String, val totalNum: String, val records: List<Record>,
        val number: Int = 1, val year: String = "", val rawJson: String = ""
    )
    sealed class Outcome {
        data class Success(val page: Page) : Outcome()
        data class Failure(val message: String) : Outcome()
    }
    sealed class DetailOutcome {
        data class Success(
            val rawJson: String, val fetchedAt: Long = System.currentTimeMillis(),
            /** 记录自带的加密轨迹文件（OSS），可能为空。 */
            val fileText: String = "", val fileRef: String = ""
        ) : DetailOutcome()
        data class Failure(val message: String) : DetailOutcome()
    }

    fun parsePage(data: JSONObject, year: String, page: Int): Page {
        val list = data.optJSONArray("list") ?: data.optJSONArray("record_list") ?: error("Missing history list")
        val records = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            Record(
                item.optString("run_zone_name").ifBlank { item.optString("title").ifBlank { item.optString("name") } },
                item.optString("distance").ifBlank { item.optString("original_distance") }, item.optString("rate"),
                item.optString("start_time"), item.optString("used_time").ifBlank { item.optString("time") },
                item.optLong("id", item.optLong("record_id")), item.optString("uid"), year,
                LepaoRunData.redacted(item).toString(),
                recordStatus = item.optString("record_status"),
                errorType = item.optString("error_type")
            )
        }
        return Page(data.optString("date"), data.optString("total_num"), records, page, year,
            LepaoRunData.redacted(data).toString())
    }

    fun fetch(
        sessionJson: String, year: String = Calendar.getInstance().get(Calendar.YEAR).toString(),
        page: Int = 1, deviceId: String, model: String, osVersion: String, schoolId: String
    ): Outcome = try {
        val data = request("Run2/allRecordList", sessionJson,
            mapOf("page" to page.toString(), "year" to year), deviceId, model, osVersion, schoolId)
        Outcome.Success(parsePage(data, year, page))
    } catch (e: Exception) { Outcome.Failure(e.message ?: "History request failed") }

    fun detail(
        sessionJson: String, record: Record, deviceId: String, model: String, osVersion: String, schoolId: String
    ): DetailOutcome = try {
        val session = JSONObject(sessionJson)
        val uid = session.optString("user_id").ifBlank { session.optString("uid") }
        require(record.recordId > 0 && record.year.matches(Regex("[0-9]{4}"))) { "Invalid record identity" }
        // 记录列表 uid 缺失时只允许当前账号；返回了不同 uid 时不跨账号查询。
        require(record.ownerId.isBlank() || record.ownerId == uid) { "该记录属于另一个账号，请先刷新历史" }
        val data = request("Run/recordDetailV270", sessionJson, mapOf(
            "record_id" to record.recordId.toString(), "record_uid" to uid, "year_num" to record.year
        ), deviceId, model, osVersion, schoolId)
        val detail = data.optJSONObject("detail") ?: data
        require(detail.optString("uid").let { it.isBlank() || it == uid }) { "Response identity mismatch" }
        // 详情只回打卡点，整条轨迹在 file 指向的加密文件里；redacted 会剪掉 URL 查询串，所以先取原值。
        val fileRef = detail.optString("file").ifBlank { data.optString("file") }
        val fileText = if (fileRef.startsWith("http")) LepaoReadClient.fetchText(fileRef).orEmpty() else ""
        DetailOutcome.Success(LepaoRunData.redacted(data).toString(), System.currentTimeMillis(), fileText, fileRef)
    } catch (e: Exception) { DetailOutcome.Failure(e.message ?: "Record detail request failed") }

    private fun request(
        path: String, sessionJson: String, extra: Map<String, String>,
        deviceId: String, model: String, osVersion: String, schoolId: String
    ): JSONObject {
        return LepaoReadClient.request(path, sessionJson, extra)
    }
}
