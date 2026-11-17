package com.kdzw8p.mqa.v71f.utils

import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 学校列表（School/nearbySchool）。
 *
 * 反编译来源：ChooseSchoolPresenter.k5() → kd.o.C4 = "School/nearbySchool"，
 * 入参 lat / lng / province / city / cache_time，返回 SchoolListResponse：
 * - list：附近学校（school_id / school_name / a）
 * - all_school_list.list：全量学校（i=id、n=名称、a=key、status），App 的搜索就是在本地按名称过滤这份列表。
 * 这里把两份合并，附近学校优先标记，按名称排序，便于直接搜索选择。
 */
object LepaoSchoolDirectory {

    private const val BASE = "https://api2.lptiyu.com/v3/api.php/"
    private const val PATH = "School/nearbySchool"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class School(
        val id: Long,
        val name: String,
        val key: String = "",
        val nearby: Boolean = false
    )

    sealed class Outcome {
        data class Success(val schools: List<School>) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    fun fetch(
        lat: String,
        lng: String,
        province: String,
        city: String,
        deviceId: String,
        model: String,
        osVersion: String
    ): Outcome {
        val params = LinkedHashMap<String, String>().apply {
            put("version", "4.7.0")
            put("version_name", "4.7.0")
            put("mobileModel", model)
            put("mobileDeviceId", deviceId)
            put("mobileOsVersion", osVersion)
            put("ostype", "1")
            put("school_id", "")
            put("uid", "1")
            put("timestamp", (System.currentTimeMillis() / 1000).toString())
            put("nonce", ((System.currentTimeMillis() % 900_000_000L) + 100_000_000L).toString())
            put("lat", lat)
            put("lng", lng)
            put("province", province)
            put("city", city)
            put("cache_time", "0")
        }
        val signed = LepaoApiCrypto.signed(params)
        val form = FormBody.Builder().apply { signed.forEach { (k, v) -> add(k, v) } }

        val request = Request.Builder()
            .url(BASE + PATH)
            .header("User-Agent", "okhttp/4.9.3")
            .post(form.build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return Outcome.Failure("HTTP ${response.code}")
                val json = JSONObject(body)
                if (json.optInt("status", -1) != 1) {
                    return Outcome.Failure(json.optString("info").ifBlank { "学校列表获取失败" })
                }
                val data = LepaoApiCrypto.dataObject(json) ?: return Outcome.Failure("学校列表为空")
                val merged = LinkedHashMap<Long, School>()

                data.optJSONArray("list")?.let { nearby ->
                    for (i in 0 until nearby.length()) {
                        val item = nearby.optJSONObject(i) ?: continue
                        val id = item.optLong("school_id", 0L)
                        val name = item.optString("school_name", "").trim()
                        if (id > 0L && name.isNotEmpty()) {
                            merged[id] = School(id, name, item.optString("a", ""), true)
                        }
                    }
                }
                data.optJSONObject("all_school_list")?.optJSONArray("list")?.let { all ->
                    for (i in 0 until all.length()) {
                        val item = all.optJSONObject(i) ?: continue
                        val id = item.optLong("i", 0L)
                        val name = item.optString("n", "").trim()
                        if (id > 0L && name.isNotEmpty() && !merged.containsKey(id)) {
                            merged[id] = School(id, name, item.optString("a", ""), false)
                        }
                    }
                }
                if (merged.isEmpty()) Outcome.Failure("学校列表为空")
                else Outcome.Success(merged.values.sortedBy { it.name })
            }
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}