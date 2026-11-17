package com.kdzw8p.mqa.v71f.utils

/** Root is used for diagnostics and one-time legacy cleanup, never configuration delivery. */
internal object SystemFileCommands {
    private const val ROOT = "/proc/1/root"
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    fun read(paths: List<String>): String {
        require(paths.isNotEmpty() && paths.all { it.startsWith("/data/") && ".." !in it.split('/') })
        return paths.joinToString(" || ") { "cat ${quote(ROOT + it)} 2>/dev/null" }
    }

    fun removeLegacyConfigs(): String = """
        set -e
        rm -f $ROOT/data/local/tmp/kdzw8p_v71f_cfg.json \
            $ROOT/data/local/tmp/kdzw8p_v71f_cfg.tmp.json \
            $ROOT/data/local/tmp/kdzw8p_v71f_cfg.json.input.* \
            $ROOT/data/local/tmp/.lsp_selinux_probe \
            $ROOT/data/local/tmp/.lsp_sepolicy_apply.sh \
            $ROOT/data/system/kdzw8p_v71f_cfg.json \
            $ROOT/data/system/kdzw8p_v71f_cfg.tmp.json \
            $ROOT/data/system/kdzw8p_v71f_cfg.json.tmp.* \
            $ROOT/data/data/com.kdzw8p.mqa.v71f/files/kdzw8p_v71f_cfg.json
        for domain in com.android.phone com.android.bluetooth; do
            rm -f "$ROOT/data/user_de/0/${'$'}domain/files/kdzw8p_v71f_cfg.json" \
                "$ROOT/data/user_de/0/${'$'}domain/files/kdzw8p_v71f_cfg.json.tmp" \
                $ROOT/data/user_de/0/${'$'}domain/files/kdzw8p_v71f_cfg.json.tmp.*
        done
    """.trimIndent()
}
