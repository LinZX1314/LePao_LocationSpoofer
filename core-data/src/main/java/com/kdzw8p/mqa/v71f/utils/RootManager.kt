package com.kdzw8p.mqa.v71f.utils

import com.kdzw8p.mqa.v71f.data.model.RootSetupTestResult
import com.kdzw8p.mqa.v71f.data.model.RootSolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootManager {

    companion object {
        private const val TAG = "R7VqzN"
    }

    suspend fun checkRootAccess(solution: RootSolution = RootSolution.AUTO): Boolean =
        withContext(Dispatchers.IO) {
            val hasRoot = executeCommand("id").contains("uid=0(root)")
            if (hasRoot) applyRootBackgroundExemptions()
            hasRoot
        }

    suspend fun applyRootBackgroundExemptions(packageName: String = "com.kdzw8p.mqa.v71f"): Boolean =
        withContext(Dispatchers.IO) {
            val cmds = """
                dumpsys deviceidle whitelist +$packageName 2>/dev/null || true
                cmd appops set $packageName RUN_IN_BACKGROUND allow 2>/dev/null || true
                cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
                cmd appops set $packageName WAKE_LOCK allow 2>/dev/null || true
                cmd appops set $packageName AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null || true
                am set-standby-bucket $packageName active 2>/dev/null || true
            """.trimIndent()
            executeCommand(cmds) != "ERROR"
        }

    /** Read-only diagnostics: no SELinux patches, probe files, or root scripts are needed. */
    suspend fun testRootSetup(solution: RootSolution = RootSolution.AUTO): RootSetupTestResult =
        withContext(Dispatchers.IO) {
            val idOutput = executeCommand("id")
            val service = XposedModuleStatus.mService
            var accessible = false
            var published = false
            var detail = "Framework disconnected"
            if (service != null) {
                try {
                    val snapshot = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                        .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                    accessible = true
                    published = snapshot != null
                    detail = if (published) "Configuration published; check Hook status for receiver confirmation"
                        else "Framework ready; no configuration published yet"
                } catch (error: Exception) {
                    detail = "${error.javaClass.simpleName}: ${error.message}"
                }
            }
            RootSetupTestResult(
                hasRoot = idOutput.contains("uid=0(root)"),
                idOutput = idOutput,
                solution = solution,
                frameworkConnected = service != null,
                remoteConfigAccessible = accessible,
                configPublished = published,
                frameworkDetail = detail
            )
        }

    /** Force-stops selected apps so their next launch loads the current module and configuration. */
    suspend fun forceStopApps(packages: List<String>): List<Pair<String, Boolean>> =
        withContext(Dispatchers.IO) {
            packages.map { pkg -> pkg to (executeCommand("am force-stop $pkg") != "ERROR") }
        }

    suspend fun grantMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.kdzw8p.mqa.v71f android:mock_location allow")
        result != "ERROR"
    }

    suspend fun revokeMockLocation(): Boolean = withContext(Dispatchers.IO) {
        val result =
            executeCommand("appops set com.kdzw8p.mqa.v71f android:mock_location default")
        result != "ERROR"
    }

    fun executeCommand(command: String): String = runCommand(command, null)

    fun executeCommandWithInput(command: String, input: String): String = runCommand(command, input)

    private fun runCommand(command: String, input: String?): String {
        // 不同 root 方案的可执行文件位置不一样，逐个尝试：
        // - Magisk / 部分 KernelSU：/system/bin/su（在 PATH 里，直接 "su"）
        // - KernelSU：/data/adb/ksu/bin/su
        // - 少数旧环境：/system/xbin/su、/su/bin/su
        // 全部失败就返回 ERROR，调用方按无 root 处理，不会卡死。
        val suPaths = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/debug_ramdisk/su"
        )
        var lastError: Exception? = null
        for (su in suPaths) {
            try {
                return runWithSu(su, command, input)
            } catch (e: Exception) {
                lastError = e
            }
        }
        android.util.Log.e(TAG, "Root command failed: no usable su binary", lastError)
        return "ERROR"
    }

    private fun runWithSu(su: String, command: String, input: String?): String {
        return try {
            val process = ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
            // Drain output while feeding stdin, so neither pipe can fill and deadlock.
            var output = ""
            val reader = kotlin.concurrent.thread(name = "R7VqzN-root-output", isDaemon = true) {
                output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    if (input != null) writer.write(input)
                }
                val exitCode = process.waitFor()
                reader.join()
                if (exitCode == 0) output.ifEmpty { "SUCCESS" }
                else {
                    android.util.Log.e(TAG, "Root command failed ($exitCode): ${output.take(2000)}")
                    "ERROR"
                }
            } finally {
                process.destroy()
            }
        } catch (e: java.io.IOException) {
            // 该 su 路径不存在/不可执行：交给上层试下一个，不要当成命令执行失败
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Root command error via $su", e)
            "ERROR"
        }
    }
}
