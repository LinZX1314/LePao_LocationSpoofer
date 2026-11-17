package com.kdzw8p.mqa.v71f

import android.app.Instrumentation
import android.os.Bundle
import com.kdzw8p.mqa.v71f.utils.LepaoUserReader

/** 从模块自己的UID验证资料读取；输出只含字段存在性，不含资料值、凭据或文件内容。 */
internal fun Instrumentation.verifyLepaoProfileRead(): Bundle {
    val output = Bundle()
    val result = LepaoUserReader.readResult()
    val user = result.user
    if (user == null) {
        output.putString("result", "FAIL: ${result.failure}")
        // 只检查本测试进程的提权身份和文件访问，不输出文件内容或个人资料。
        for ((index, executable) in listOf("su", "/system/bin/su").withIndex()) {
            val diagnostic = runCatching {
                val command = "id -u; for p in /data/user/0/com.lptiyu.tanke/files/mmkv/setting /proc/1/root/data/user/0/com.lptiyu.tanke/files/mmkv/setting; do if test -r \"\$p\"; then echo readable; else echo unreadable; fi; done"
                val process = ProcessBuilder(executable, "-c", command).redirectErrorStream(true).start()
                process.outputStream.close()
                check(process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS))
                val lines = process.inputStream.bufferedReader().use { it.readLines() }
                "exit=${process.exitValue()},root=${lines.any { it.trim() == "0" }},readable=${lines.count { it == "readable" }},unreadable=${lines.count { it == "unreadable" }}"
            }.getOrElse { "diagnostic=${it.javaClass.simpleName}" }
            output.putString("access_$index", diagnostic)
        }
        return output
    }
    output.putString("source", user.source)
    output.putBoolean("numeric_id", user.userId.matches(Regex("[0-9]+")))
    output.putBoolean("nickname_present", user.nickname.isNotBlank())
    output.putBoolean("school_present", user.school.isNotBlank())
    output.putBoolean("student_number_present", user.studentNo.isNotBlank())
    output.putBoolean("no_credential_fields", user.javaClass.declaredFields.none {
        it.name.contains("token", ignoreCase = true) || it.name == "raw"
    })
    val passed = output.getBoolean("numeric_id") && output.getBoolean("nickname_present") &&
        output.getBoolean("school_present") && output.getBoolean("no_credential_fields")
    output.putString("result", if (passed) "PASS" else "FAIL: profile fields missing")
    return output
}
