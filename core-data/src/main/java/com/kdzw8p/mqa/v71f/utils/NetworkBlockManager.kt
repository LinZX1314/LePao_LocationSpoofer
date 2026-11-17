package com.kdzw8p.mqa.v71f.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按目标应用 UID 断网（本地防火墙层）。
 *
 * 为什么不做成「全局断网 / 改 DNS」：目标要求是「WiFi 连接正常、DNS 正常，但没有外网」，
 * 这样 App 里判断网络状态的 ConnectivityManager / DNS 解析都还是通的，只有真正出站的数据包
 * 被丢弃——正好用来掐掉云端那条链路（例如服务端复核打卡点、成绩校验），而本地判定继续跑。
 *
 * ## 实现：自建命名链（不是往 OUTPUT 里塞裸规则）
 *
 * ```
 * iptables -N R7VQZN_BLOCK              # 建一条有名字的自定义链
 * iptables -I OUTPUT 1 -j R7VQZN_BLOCK  # OUTPUT 只挂一条跳转
 * iptables -A R7VQZN_BLOCK ... -j DROP  # 真正的规则全在命名的链里
 * ```
 *
 * 这样做的好处：
 *  - **可命名、可识别**：`iptables -L R7VQZN_BLOCK` 一眼看到全部规则，不散落在 OUTPUT 里
 *  - **清理是一条命令**：`-D OUTPUT -j R7VQZN_BLOCK` → `-F R7VQZN_BLOCK` → `-X R7VQZN_BLOCK`
 *    整链销毁，不需要按行号、不需要匹配规则规格，永远不会"删错"或"删不掉"
 *  - **幂等**：重复点按钮就是重建一次链，不会堆叠
 *  - **不污染系统链**：OUTPUT 里最多只有一条 `-j R7VQZN_BLOCK`
 *
 * 链内规则顺序：DNS(53/tcp+udp) ACCEPT → 其余 DROP → 链尾 RETURN。
 * 所以目标 App 的域名解析照常成功，只是所有实际业务请求都发不出去，
 * 表现正好是「WiFi 正常、DNS 正常、就是没网」。
 *
 * ⚠ 链是内核状态，不会随 App 卸载消失。清理兜底有四处：
 * LocationApp.onCreate()、recoverAfterBoot()、stopSpoofing()、FloatingJoystickService.onDestroy()。
 * App 装不上/root 被撤时，用 tools/r7vqn_netblock_purge.sh 手动清：
 *   adb root && adb shell iptables -D OUTPUT -j R7VQZN_BLOCK; iptables -F R7VQZN_BLOCK; iptables -X R7VQZN_BLOCK
 */
class NetworkBlockManager(private val context: Context) {

    companion object {
        /** 自定义链名：≤28 字符，全大写便于 `iptables -L` 里一眼识别 */
        const val CHAIN = "R7VQZN_BLOCK"

        private const val PREFS = "app_settings"
        private const val KEY_BLOCKED = "netblock_targets"
        private const val KEY_ENABLED = "netblock_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = RootManager()

    /** 当前是否有任意目标处于断网状态（进程重启后从 SP 恢复展示） */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 已断网的目标包名集合 */
    fun blockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    private fun saveBlocked(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, packages).apply()
    }

    /**
     * 切换单个目标包的断网状态；返回切换后是否处于断网。
     * packageName 传目标 App（例如乐跑 com.lptiyu.tanke）。
     */
    suspend fun toggle(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val blocked = blockedPackages().toMutableSet()
        if (packageName in blocked) {
            blocked.remove(packageName)
            saveBlocked(blocked)
            val ok = rebuildChain(blocked)
            isEnabled = blocked.isNotEmpty() && ok
            false
        } else {
            blocked.add(packageName)
            if (rebuildChain(blocked)) {
                saveBlocked(blocked)
                isEnabled = true
                true
            } else {
                // 下发失败就回滚内存状态，避免按钮显示"已断网"但其实没生效
                blocked.remove(packageName)
                isEnabled = blocked.isNotEmpty()
                false
            }
        }
    }

    /** 解除全部目标的断网（App 退出/一键关掉时兜底） */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        val ok = destroyChain()
        saveBlocked(emptySet())
        isEnabled = false
        ok
    }

    /**
     * 无差别销毁本模块的自定义链。
     * 卸载重装、升级、UID 变化、SP 丢数据这些情况下，残留都靠它兜底——
     * 因为清理只认链名 R7VQZN_BLOCK，跟包名/UID/规则内容全都无关。
     */
    suspend fun purgeOrphans(): Boolean = withContext(Dispatchers.IO) { destroyChain() }

    /** 开机/进程重建后按持久化状态重放，避免 iptables 被清空后静默失效 */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val blocked = blockedPackages()
        if (blocked.isEmpty()) {
            destroyChain()
            isEnabled = false
            return@withContext true
        }
        val ok = rebuildChain(blocked)
        isEnabled = ok
        ok
    }

    /** 供诊断页展示：当前链里到底有什么（空表示没断网） */
    suspend fun describeChain(): String = withContext(Dispatchers.IO) {
        val out = root.executeCommand(
            "iptables -L $CHAIN -n 2>/dev/null; echo ---; ip6tables -L $CHAIN -n 2>/dev/null"
        )
        if (out == "ERROR") "" else out
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 重建命名链：先彻底销毁，再按 blocked 集合重新建。
     * 一条命令完成「拆 + 建」，天然幂等，重复调用不会堆叠。
     */
    private fun rebuildChain(blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return destroyChain()
        val uids = blocked.mapNotNull { pkg -> resolveUid(pkg).takeIf { it >= 0 } }
        if (uids.isEmpty()) return false

        val script = buildString {
            append("for t in iptables ip6tables; do ")
            // 1) 先整链拆掉（跳转 → 清空 → 删链），顺序不能反
            append("while \$t -D OUTPUT -j $CHAIN 2>/dev/null; do :; done; ")
            append("\$t -F $CHAIN 2>/dev/null; \$t -X $CHAIN 2>/dev/null; ")
            // 2) 建链
            append("\$t -N $CHAIN; ")
            // 3) 规则写进链里：每个 UID 先放行 DNS，再 DROP 其余
            for (uid in uids) {
                append("\$t -A $CHAIN -m owner --uid-owner $uid -p udp --dport 53 -j ACCEPT; ")
                append("\$t -A $CHAIN -m owner --uid-owner $uid -p tcp --dport 53 -j ACCEPT; ")
                append("\$t -A $CHAIN -m owner --uid-owner $uid -j DROP; ")
            }
            // 4) 链尾兜底 RETURN，避免误伤未列出的流量
            append("\$t -A $CHAIN -j RETURN; ")
            // 5) OUTPUT 只挂一条跳转
            append("\$t -I OUTPUT 1 -j $CHAIN; ")
            append("done")
        }
        val result = root.executeCommand(script)
        if (result == "ERROR") return false
        // 校验链真的建起来了，防止 su 半途失败时按钮显示已断网
        val check = root.executeCommand("iptables -L $CHAIN -n 2>/dev/null | head -3")
        return check != "ERROR" && check.contains(CHAIN)
    }

    /** 彻底销毁命名链：跳转 → 清空 → 删链，IPv4/IPv6 都做，幂等 */
    private fun destroyChain(): Boolean {
        val script = buildString {
            append("for t in iptables ip6tables; do ")
            append("while \$t -D OUTPUT -j $CHAIN 2>/dev/null; do :; done; ")
            append("\$t -F $CHAIN 2>/dev/null; ")
            append("\$t -X $CHAIN 2>/dev/null; ")
            append("done")
        }
        return root.executeCommand(script) != "ERROR"
    }

    /** 用 `cmd package list packages -U` 取 UID；拿不到就返回 -1，绝不瞎猜 */
    private fun resolveUid(packageName: String): Int {
        val out = root.executeCommand("cmd package list packages -U $packageName")
        val match = Regex("""package:$packageName\s+uid:(\d+)""").find(out) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }
}
