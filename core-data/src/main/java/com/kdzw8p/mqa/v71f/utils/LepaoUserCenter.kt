package com.kdzw8p.mqa.v71f.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 乐跑个人主页统计（User/myZone）。
 *
 * 反编译来源：UserCenterPresenter.r0() → kd.o.C6 = "User/myZone"，入参 visit_uid（rc.c.F5），
 * 公共参数带 uid/token；返回 UserCenter：
 * - running_stat.total.total_valid_num / total_valid_distance（累计次数、累计里程）
 * - running_stat.week.week_num / week_distance（本周）
 * - user_info.avatar / nick_name（当前有效的头像地址）
 * 响应 data 与其它 v3 接口一样是 NEW_GAME AES 密文，用 LepaoApiCrypto 解密。
 */
object LepaoUserCenter {

    data class Stats(
        val totalRuns: Int,
        val totalDistanceM: Double,
        val weekRuns: Int,
        val weekDistanceM: Double,
        val avatarUrl: String,
        val nickname: String,
        val targetRuns: Int = 0,
        val venueId: Long = 0L,
        val venueName: String = "",
        /** 本学期已跑 / 要求次数（run_rule.run_num / total_num），表格的「次数/目标」优先用它 */
        val termRuns: Int = 0,
        val termTarget: Int = 0,
        val rawJson: String = ""
    )

    sealed class Outcome {
        data class Success(val stats: Stats) : Outcome()
        data class Failure(val message: String, val status: Int = 0) : Outcome() {
            /**
             * 服务端明确判定登录票失效：101/102，或 status=0 但 info 是「登录信息失效」。
             * 这种情况只能重新扫码授权换新票，重写旧票没用。
             */
            val sessionInvalid: Boolean get() = status == 101 || status == 102 ||
                message.contains("登录信息失效") || message.contains("请重新登录") ||
                message.contains("重新登录")

            /** 版本门槛不是会话问题，重扫码也无效；客户端需按当前最低版本上报。 */
            val versionBlocked: Boolean get() =
                message.contains("版本过低") || message.contains("请升级后使用")
        }
    }

    suspend fun fetch(
        sessionJson: String,
        deviceId: String,
        model: String,
        osVersion: String,
        schoolId: String
    ): Outcome {
        val session = try {
            JSONObject(sessionJson)
        } catch (_: Exception) {
            return Outcome.Failure("登录态不是有效 JSON")
        }
        val uid = session.optString("user_id").ifBlank { session.optString("uid") }
        val token = session.optString("token")
            .ifBlank { session.optString("access_token") }
        if (!LepaoRunData.isAccountId(uid) || token.isBlank()) return Outcome.Failure("登录态缺少有效 user_id / token")

        return try {
            val (data, accountInfoResult) = coroutineScope {
                val zone = async(Dispatchers.IO) {
                    LepaoReadClient.request("User/myZone", sessionJson, mapOf("visit_uid" to uid))
                }
                // User/User 只补充资料，不依赖 myZone 的响应；并发读取后仍保留原有失败降级。
                val accountInfo = async<Pair<Any?, LepaoReadClient.Failure?>>(Dispatchers.IO) {
                    try {
                        LepaoRunData.redacted(LepaoReadClient.request("User/User", sessionJson)) to null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: LepaoReadClient.Failure) {
                        null to e
                    }
                }
                zone.await() to accountInfo.await()
            }
            val (accountInfo, accountInfoFailure) = accountInfoResult
            if (accountInfo != null) {
                data.put("account_info", accountInfo)
            } else {
                accountInfoFailure?.let { data.put("account_info_error", "${it.status}: ${it.message}") }
            }
            // 个人资料包含服务端原始判定字段（如 cheat_type），不从申诉选项推导。
            val total = data.optJSONObject("running_stat")?.optJSONObject("total")
            val week = data.optJSONObject("running_stat")?.optJSONObject("week")
            val user = data.optJSONObject("user_info")
            val rule = data.optJSONObject("run_rule")
                Outcome.Success(
                    Stats(
                        totalRuns = total?.optInt("total_valid_num", 0) ?: 0,
                        totalDistanceM = (total?.optDouble("total_valid_distance", 0.0) ?: 0.0) * 1000.0,
                        weekRuns = week?.optInt("week_num", 0) ?: 0,
                        weekDistanceM = (week?.optDouble("week_distance", 0.0) ?: 0.0) * 1000.0,
                        avatarUrl = user?.optString("avatar", "").orEmpty(),
                        nickname = user?.optString("nick_name", "").orEmpty(),
                        targetRuns = total?.optInt("total_num", 0)
                            ?: rule?.optInt("total_num", 0)
                            ?: 0,
                        venueId = data.optLong("run_zone_id", 0L)
                            .takeIf { it > 0 }
                            ?: rule?.optLong("run_zone_id", 0L)
                            ?: 0L,
                        venueName = data.optString("run_zone_name")
                            .ifBlank { rule?.optString("run_zone_name", "").orEmpty() },
                        termRuns = rule?.optInt("total_num", 0) ?: 0,
                        termTarget = rule?.optInt("term_num", 0) ?: 0,
                        rawJson = LepaoRunData.redacted(data).toString()
                    )
                )
        } catch (e: LepaoReadClient.Failure) {
            Outcome.Failure(e.message ?: "Request failed", e.status)
        } catch (e: Exception) {
            Outcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}