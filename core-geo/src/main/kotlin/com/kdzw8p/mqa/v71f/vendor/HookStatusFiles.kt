package com.kdzw8p.mqa.v71f.vendor

/**
 * 全局方案 Hook 运行状态报告的落盘位置，xposed 模块写、App 通过 root 读，两边共用这一份路径。
 * 键是 xposed 模块 `SystemProcess` 的枚举名；每个进程优先写 files 目录，不可写时退回应用数据目录本身
 * （两者都属于该进程自己的 uid，SELinux 上也是它本域可写的数据目录）。
 */
object HookStatusFiles {

    val PATHS: Map<String, List<String>> = linkedMapOf(
        "SYSTEM_SERVER" to listOf("/data/system/kdzw8p_v71f_st.json"),
        "PHONE" to listOf(
            "/data/user_de/0/com.android.phone/files/kdzw8p_v71f_st.json",
            "/data/user_de/0/com.android.phone/kdzw8p_v71f_st.json"
        ),
        "BLUETOOTH" to listOf(
            "/data/user_de/0/com.android.bluetooth/files/kdzw8p_v71f_st.json",
            "/data/user_de/0/com.android.bluetooth/kdzw8p_v71f_st.json"
        ),
    )

    fun paths(process: String): List<String> = PATHS[process].orEmpty()
}
