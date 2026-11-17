package com.kdzw8p.mqa.v71f

import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.kdzw8p.mqa.v71f.data.repository.HookStatusRepository
import com.kdzw8p.mqa.v71f.utils.FrameworkConfigChannel
import com.kdzw8p.mqa.v71f.utils.RootManager
import com.kdzw8p.mqa.v71f.utils.XposedModuleStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Checks framework configuration and system reports without changing simulation state. */
class SystemFilesInstrumentation : Instrumentation() {
    private var environmentRefresh = false
    private var moduleRuntime = false
    private var moduleHotReload = false
    private var simulationContract = false
    private var lepaoProfileRead = false
    private var lepaoRules = false
    private var lepaoRulesOffline = false
    private var lepaoRenewal = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        environmentRefresh = arguments?.getString("check") == "environment-refresh"
        moduleRuntime = arguments?.getString("check") == "module-runtime"
        moduleHotReload = arguments?.getString("check") == "module-hot-reload"
        simulationContract = arguments?.getString("check") == "simulation-contract"
        lepaoProfileRead = arguments?.getString("check") == "lepao-profile-read"
        lepaoRenewal = arguments?.getString("check") == "lepao-renewal"
        lepaoRulesOffline = arguments?.getString("check") == "lepao-rules-offline"
        lepaoRules = arguments?.getString("check") == "lepao-rules" || lepaoRulesOffline
        start()
    }

    override fun onStart() {
        if (lepaoRenewal) {
            val output = verifyLepaoRenewal()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (lepaoRules) {
            val output = verifyLepaoRules(lepaoRulesOffline)
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (lepaoProfileRead) {
            val output = verifyLepaoProfileRead()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (simulationContract) {
            val output = verifySimulationContract()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (moduleHotReload) {
            val output = verifyModuleHotReload()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (moduleRuntime) {
            val output = verifyModuleRuntime()
            finish(if (output.getString("result") == "PASS") -1 else 0, output)
            return
        }
        if (environmentRefresh) {
            val output = verifyEnvironmentRefresh()
            finish(if (output.getString("result")?.startsWith("PASS") == true) -1 else 0, output)
            return
        }
        val output = Bundle()
        try {
            val service = runBlocking {
                repeat(150) {
                    XposedModuleStatus.mService?.let { return@runBlocking it }
                    delay(100)
                }
                error("Framework did not connect; enable the module and restart the device")
            }
            val text = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                ?: error("No framework configuration; start a simulation first")
            val envelope = JSONObject(text)
            check(envelope.getInt("version") == FrameworkConfigChannel.VERSION)
            val file = envelope.optString("file")
            val payload = if (file.isEmpty()) envelope.getString("payload") else {
                check(FrameworkConfigChannel.isConfigFile(file))
                ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(file))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            check(JSONObject(payload).has("active"))
            val root = RootManager()
            val noLegacyTmp = root.executeCommand("""
                for name in kdzw8p_v71f_cfg.json kdzw8p_v71f_cfg.tmp.json .lsp_selinux_probe .lsp_sepolicy_apply.sh; do
                    test ! -e /proc/1/root/data/local/tmp/${'$'}name || exit 1
                done
            """.trimIndent())
            check(noLegacyTmp != "ERROR") { "Legacy tmp artifacts remain or root access is unavailable" }
            if (BuildConfig.GLOBAL_SCHEME) {
                val expected = buildSet {
                    if (service.scope.any { it == "system" || it == "android" }) add("SYSTEM_SERVER")
                    if ("com.android.phone" in service.scope) add("PHONE")
                    if ("com.android.bluetooth" in service.scope) add("BLUETOOTH")
                }
                check(expected.isNotEmpty()) { "No system receivers enabled in module scope" }
                val reports = runBlocking {
                    val repository = HookStatusRepository(root)
                    // Reconnecting the app may republish its snapshot; status files are debounced.
                    // Wait for that publication instead of comparing an immediately stale report.
                    repeat(40) {
                        val current = repository.readAll().filterKeys { it in expected }
                        val latest = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
                            .getString(FrameworkConfigChannel.SNAPSHOT_KEY, null)
                            ?.let { JSONObject(it).getLong("published_at") }
                        if (latest != null && current.keys == expected && current.values.all {
                                it?.configPath?.startsWith("libxposed:") == true && it.configModified == latest &&
                                    it.errors.isEmpty() && it.hookedCount > 0
                            }) return@runBlocking current
                        delay(250)
                    }
                    error("Enabled system receivers have not converged: $expected")
                }
                output.putString("receivers", reports.keys.joinToString())
            }
            output.putString("transport", if (file.isEmpty()) "remote-preferences" else "remote-file")
            output.putString("result", "PASS")
            finish(-1, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.stackTraceToString()}")
            finish(0, output)
        }
    }
}
