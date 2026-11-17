package com.kdzw8p.mqa.v71f.utils

/** Shared wire contract for the module app and injected processes. */
object FrameworkConfigChannel {
    const val GROUP = "kdzw8p_v71f_cfg"
    const val SNAPSHOT_KEY = "snapshot"
    const val VERSION = 1
    // Leave ample room for Binder's other in-flight transactions. Larger data uses an FD.
    const val INLINE_LIMIT_BYTES = 128 * 1024
    const val FILE_PREFIX = "kdzw8p_v71f_cfg-"

    fun isConfigFile(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(".json") &&
            '/' !in name && '\\' !in name && ".." !in name
}
