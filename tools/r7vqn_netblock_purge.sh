#!/system/bin/sh
# R7VqzN 断网规则清理工具（独立脚本，不依赖 App、不依赖 su）
#
# 本模块的断网实现用的是**命名自定义链** R7VQZN_BLOCK：
#   iptables -I OUTPUT 1 -j R7VQZN_BLOCK
#   iptables -A R7VQZN_BLOCK ... -j DROP
# 所以清理只需要「摘跳转 → 清空 → 删链」三步，不需要按行号、不需要匹配规则内容。
#
# 什么时候用：
#   App 被卸载/覆盖安装/进程被杀/root 被撤，内核里可能还留着这条链。
#
# 用法：
#   1) 电脑（不需要 su）：
#        adb root
#        adb push tools/r7vqn_netblock_purge.sh /data/local/tmp/
#        adb shell sh /data/local/tmp/r7vqn_netblock_purge.sh
#   2) 手机终端（KernelSU / Magisk）：
#        su -c 'sh /data/local/tmp/r7vqn_netblock_purge.sh'

CHAIN="R7VQZN_BLOCK"

echo "=== 清理前 ==="
iptables  -S 2>/dev/null | grep "$CHAIN" || echo "(IPv4 无残留)"
ip6tables -S 2>/dev/null | grep "$CHAIN" || echo "(IPv6 无残留)"

for t in iptables ip6tables; do
    # 顺序不能反：先摘掉所有指向它的跳转，否则链被引用着删不掉
    while $t -D OUTPUT -j "$CHAIN" 2>/dev/null; do :; done
    $t -F "$CHAIN" 2>/dev/null
    $t -X "$CHAIN" 2>/dev/null
done

echo "=== 清理后 ==="
iptables  -S 2>/dev/null | grep "$CHAIN" || echo "(IPv4 已清除)"
ip6tables -S 2>/dev/null | grep "$CHAIN" || echo "(IPv6 已清除)"
echo "=== done ==="
