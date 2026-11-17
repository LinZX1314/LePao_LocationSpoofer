package com.kdzw8p.mqa.v71f.utils

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 当前设备资料的只读快照。[readResult] 只解码资料字段，不导出凭据；
 * [readSessionResult] 仅在用户主动点「从乐跑直接读取」时取登录态，用于本地会话注入，token 不打印、不落日志。
 */
object LepaoUserReader {
    enum class Failure { ROOT_UNAVAILABLE, FILE_UNREADABLE, TIMEOUT, INVALID_FORMAT, NO_PROFILE, CHANGING }
    data class Result(val user: LepaoUser? = null, val failure: Failure? = null)

    data class LepaoUser(
        val userId: String,
        val nickname: String = "",
        val school: String = "",
        val studentNo: String = "",
        val phone: String = "",
        val role: String = "",
        val sex: String = "",
        /** 头像地址；服务端没下发时为空 */
        val avatarUrl: String = "",
        /** 已登记的人脸照片地址（run_rule.face_img） */
        val faceImage: String = "",
        /** 本学期已跑次数 / 要求次数 */
        val runCount: Int = 0,
        val totalRunCount: Int = 0,
        val totalDistanceM: Double = 0.0,
        val runScore: String = "",
        /** 当前生效场地 */
        val venueId: Long = 0L,
        val venueName: String = "",
        val venueCoverUrl: String = "",
        /** 学校 id，直登时预填 school_id */
        val schoolId: String = "",
        val source: String = "mmkv_readonly",
        val capturedAt: Long = System.currentTimeMillis(),
        val runRuleJson: String = ""
    ) {
        val isEmpty: Boolean get() = userId.isBlank()
        override fun toString(): String = "LepaoUserReader.LepaoUser(personal-data=redacted)"
    }

    /** 在IO线程调用；两次同内容快照避免宿主正追加数据时返回半条资料。 */
    fun readResult(): Result {
        val snapshot = readSnapshot()
        val bytes = snapshot.bytes ?: return Result(failure = snapshot.failure ?: Failure.ROOT_UNAVAILABLE)
        try {
            val profile = LepaoProfileSnapshot.decode(bytes)
            return Result(user = LepaoUser(profile.userId, profile.nickname, profile.school, profile.studentNo,
                profile.phone, profile.role, profile.sex, profile.avatarUrl, profile.faceImage,
                profile.runCount, profile.totalRunCount, profile.totalDistanceM, profile.runScore,
                profile.venueId, profile.venueName, profile.venueCoverUrl, profile.schoolId,
                runRuleJson = profile.runRuleJson))
        } catch (e: LepaoProfileSnapshot.Invalid) {
            return Result(failure = if (e.problem == LepaoProfileSnapshot.Problem.NO_PROFILE) Failure.NO_PROFILE else Failure.INVALID_FORMAT)
        } finally {
            bytes.fill(0)
        }
    }

    data class SessionResult(val sessionJson: String? = null, val failure: Failure? = null)

    /**
     * 直读登录态：乐跑已经登录过时，直接从它的 MMKV 取 user_id + token，生成与
     * 页面手工填写相同的会话 JSON，不再依赖 PC 端工具导出。凭据不落日志。
     */
    fun readSessionResult(): SessionResult {
        val snapshot = readSnapshot()
        val bytes = snapshot.bytes ?: return SessionResult(failure = snapshot.failure ?: Failure.ROOT_UNAVAILABLE)
        try {
            return SessionResult(sessionJson = LepaoProfileSnapshot.decodeSession(bytes).toConfigJson())
        } catch (e: LepaoProfileSnapshot.Invalid) {
            return SessionResult(
                failure = if (e.problem == LepaoProfileSnapshot.Problem.NO_PROFILE) Failure.NO_PROFILE else Failure.INVALID_FORMAT
            )
        } finally {
            bytes.fill(0)
        }
    }

    private class Snapshot(val bytes: ByteArray? = null, val failure: Failure? = null)

    /** 找一个可用的 su；同一文件连读两次内容一致才采信，避免宿主正追加数据时拿到半条记录 */
    private fun readSnapshot(): Snapshot {
        var last = Failure.ROOT_UNAVAILABLE
        for (su in SU_PATHS) {
            try {
                val first = readBytes(su)
                val second = readBytes(su)
                try {
                    if (!first.contentEquals(second)) return Snapshot(failure = Failure.CHANGING)
                    return Snapshot(bytes = second.copyOf())
                } finally {
                    first.fill(0)
                    second.fill(0)
                }
            } catch (e: ReadFailure) {
                if (e.failure == Failure.FILE_UNREADABLE || e.failure == Failure.INVALID_FORMAT) {
                    return Snapshot(failure = e.failure)
                }
                last = e.failure
            } catch (_: Exception) {
                last = Failure.ROOT_UNAVAILABLE
            }
        }
        return Snapshot(failure = last)
    }

    private val SU_PATHS = listOf(
        "su", "/system/bin/su", "/system/xbin/su", "/su/bin/su", "/data/adb/ksu/bin/su", "/debug_ramdisk/su"
    )

    private class ReadFailure(val failure: Failure) : Exception()

    private fun readBytes(su: String): ByteArray {
        // 部分root环境保留调用应用的挂载视图；系统根视图指向同一文件，不改变文件权限。
        val command = "if test -r /data/user/0/com.lptiyu.tanke/files/mmkv/setting; then " +
            "head -c 1048577 /data/user/0/com.lptiyu.tanke/files/mmkv/setting; " +
            "elif test -r /proc/1/root/data/user/0/com.lptiyu.tanke/files/mmkv/setting; then " +
            "head -c 1048577 /proc/1/root/data/user/0/com.lptiyu.tanke/files/mmkv/setting; else exit 41; fi"
        val process = ProcessBuilder(su, "-c", command).start()
        val output = ByteArrayOutputStream()
        var overflow = false
        var failed = false
        val stdout = kotlin.concurrent.thread(isDaemon = true, name = "lepao-profile-read") {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > LepaoProfileSnapshot.MAX_FILE_BYTES) overflow = true
                            if (!overflow) output.write(buffer, 0, count)
                        }
                    } finally { buffer.fill(0) }
                }
            } catch (_: Exception) { failed = true }
        }
        val stderr = kotlin.concurrent.thread(isDaemon = true, name = "lepao-profile-status") {
            runCatching { process.errorStream.use { input -> val buffer = ByteArray(1024); while (input.read(buffer) >= 0) { }; buffer.fill(0) } }
        }
        try {
            process.outputStream.close()
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw ReadFailure(Failure.TIMEOUT)
            stdout.join(1000)
            stderr.join(1000)
            if (stdout.isAlive || stderr.isAlive) throw ReadFailure(Failure.TIMEOUT)
            if (process.exitValue() == 41) throw ReadFailure(Failure.FILE_UNREADABLE)
            if (process.exitValue() != 0) throw ReadFailure(Failure.ROOT_UNAVAILABLE)
            if (overflow || failed) throw ReadFailure(Failure.INVALID_FORMAT)
            return output.toByteArray()
        } finally {
            process.destroy()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
