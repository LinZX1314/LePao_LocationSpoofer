package com.kdzw8p.mqa.v71f.xposed

/**
 * native 层 shell spoof 的 Java 桥（隐藏开关 native_shell_spoof 的入口）。
 *
 * 由 [com.kdzw8p.mqa.v71f.xposed.hooks.LepaoDetectionHooker.loadNativeShellSpoof] 在
 * System.loadLibrary("ls_spoofer_native") 之后调用，把两张表的路径灌进 C++ 侧：
 * - mapsFilePath：Java 层过滤后的 /proc/self/maps 副本文件，C 级
 *   fopen("/proc/.../maps") 被替换体改道到这份副本，壳层 native 的 isHooked
 *   读不到 LSPosed 相关映射行；
 * - propsFilePath：Java 层覆写表（ro.kernel.qemu 等），C 级 __system_property_get
 *   命中时返回表内值，未命中原样放行。
 *
 * 传路径而非全文：maps 副本可能几十 KB，走文件避免 JNI 字符串拷贝，
 * 且 C 侧改道目标就是再 fopen 一次，路径天然是接口。
 */
object LepaoDetectionHookerNative {

    @JvmStatic
    fun configure(mapsFilePath: String, propsFilePath: String) {
        nativeConfigure(mapsFilePath, propsFilePath)
    }

    /**
     * 在乐跑进程内直接读 MMKV 并导出到 /sdcard/Download/lepao_user.json。
     * 返回写入的字段数；<0 表示失败（-1 打不开文件、-2 无匹配字段、-3 写不了目标）。
     */
    @JvmStatic
    fun exportUser(): Int = try {
        nativeExportUser()
    } catch (_: Throwable) {
        -1
    }

    private external fun nativeConfigure(mapsPath: String, propsPath: String)
    private external fun nativeExportUser(): Int
}
