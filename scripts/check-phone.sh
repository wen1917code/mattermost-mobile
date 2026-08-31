#!/bin/bash
# StruGGle 真机状态巡检（无线 ADB，vivo 专用但任何设备通用）
# 用法: ./check-phone.sh
set -u
ADB=/usr/lib/android-sdk/platform-tools/adb
DEV=$($ADB devices | awk '$2=="device" && $1!~/emulator/ {print $1}' | head -1)
if [ -z "$DEV" ]; then
    echo "✗ 未找到真机。检查：手机无线调试是否开启、是否同一局域网"
    exit 1
fi
echo "设备: $DEV  ($($ADB -s $DEV shell getprop ro.product.model 2>/dev/null))"
echo "=================================================================="
echo "--- 版本 ---"
$ADB -s $DEV shell "dumpsys package com.wen.struggle" 2>/dev/null | grep -m2 -E "versionName|versionCode"
echo "--- 进程 ---"
$ADB -s $DEV shell "ps -A" 2>/dev/null | grep 'com.wen.struggle$' || echo "✗ 进程未运行！"
echo "--- 前台服务 ---"
$ADB -s $DEV shell "dumpsys activity services com.wen.struggle" 2>/dev/null | grep -m1 "isForeground" || echo "✗ KeepAliveService 未运行！"
echo "--- 通知权限 ---"
$ADB -s $DEV shell "dumpsys package com.wen.struggle" 2>/dev/null | grep -m1 "POST_NOTIFICATIONS: granted" || echo "✗ 通知权限未授予！"
echo "--- Doze 白名单 ---"
echo "    count=$($ADB -s $DEV shell 'dumpsys deviceidle whitelist' 2>/dev/null | grep -c struggle) (1=在白名单)"
echo "--- 最近 WS 事件（原生通道） ---"
$ADB -s $DEV logcat -d -s KeepAliveWS 2>/dev/null | tail -3
echo "--- 最近策略评估 ---"
$ADB -s $DEV logcat -d -s KeepAlive 2>/dev/null | grep "evaluate" | tail -2
echo "--- 最近消息通知（排除保活通知） ---"
$ADB -s $DEV shell "dumpsys notification --noredact" 2>/dev/null | grep "NotificationRecord" | grep struggle | grep -vE "ranker|Aggregate|\|10001\|" | tail -3
echo "=================================================================="
echo "提示: 发测试消息用 ./send-test-msg.sh；强制深睡测 Doze:"
echo "    $ADB -s $DEV shell 'dumpsys deviceidle force-idle'   # 进入"
echo "    $ADB -s $DEV shell 'dumpsys deviceidle unforce'      # 退出"
