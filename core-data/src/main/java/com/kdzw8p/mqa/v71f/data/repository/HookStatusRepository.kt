package com.kdzw8p.mqa.v71f.data.repository

import com.kdzw8p.mqa.v71f.data.model.HookStatusReport
import com.kdzw8p.mqa.v71f.utils.SystemFileCommands
import com.kdzw8p.mqa.v71f.utils.RootManager
import com.kdzw8p.mqa.v71f.vendor.HookStatusFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 通过 root 读取全局方案各系统进程写出的 Hook 运行状态报告 */
class HookStatusRepository(private val rootManager: RootManager) {

    /** 按进程顺序返回；某个进程没有报告（从未生效或文件不可读）时对应项为 null */
    suspend fun readAll(): Map<String, HookStatusReport?> = withContext(Dispatchers.IO) {
        HookStatusFiles.PATHS.mapValues { (_, paths) ->
            val output = rootManager.executeCommand(SystemFileCommands.read(paths))
            HookStatusReport.parse(output)
        }
    }
}
