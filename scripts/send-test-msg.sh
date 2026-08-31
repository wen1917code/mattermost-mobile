#!/bin/bash
# StruGGle 测试消息发送器：以 testbot01 身份给 wen 发一条带毫秒时间戳的私信
# 手机上收到通知 = 端到端链路正常
#
# 用法:
#   ./send-test-msg.sh                # 默认消息
#   ./send-test-msg.sh "自定义内容"    # 自定义正文（自动附时间戳）
#
# 说明: token 缓存于 ~/.struggle_bot_token（失效自动重登）；UA 必须带白名单标识，
#       否则服务端 nginx 403。
set -u
API="https://sg.ant.wenzi.uno/api/v4"
UA="User-Agent: StruGGle/1.0"
BOT_USER="testbot01"
BOT_PASS='TestBot#2026pass'
TO_USER="wen"
TOKEN_CACHE="$HOME/.struggle_bot_token"

# 1. 取 bot token（缓存有效则复用，避免每次登录堆积 session）
TOKEN=""
[ -f "$TOKEN_CACHE" ] && TOKEN=$(cat "$TOKEN_CACHE")
if [ -n "$TOKEN" ]; then
    CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$API/users/me" \
        -H "Authorization: Bearer $TOKEN" -H "$UA")
    [ "$CODE" != "200" ] && TOKEN=""
fi
if [ -z "$TOKEN" ]; then
    HDRS=$(curl -s -i --max-time 10 -X POST "$API/users/login" -H "$UA" \
        -H 'Content-Type: application/json' \
        -d "{\"login_id\":\"$BOT_USER\",\"password\":\"$BOT_PASS\"}")
    TOKEN=$(echo "$HDRS" | grep -i '^token:' | tr -d '\r' | awk '{print $2}')
    if [ -z "$TOKEN" ]; then echo "✗ bot 登录失败（检查服务器可达性）"; exit 1; fi
    umask 077 && echo "$TOKEN" > "$TOKEN_CACHE"
fi
AUTH="Authorization: Bearer $TOKEN"

# 2. 双方 user id 与 DM 频道（创建是幂等的）
BOT_ID=$(curl -s --max-time 10 "$API/users/username/$BOT_USER" -H "$AUTH" -H "$UA" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
TO_ID=$(curl -s --max-time 10 "$API/users/username/$TO_USER" -H "$AUTH" -H "$UA" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
[ -z "$BOT_ID" ] || [ -z "$TO_ID" ] && { echo "✗ 查询用户失败"; exit 1; }
DM_ID=$(curl -s --max-time 10 -X POST "$API/channels/direct" -H "$AUTH" -H "$UA" \
    -H 'Content-Type: application/json' -d "[\"$BOT_ID\",\"$TO_ID\"]" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

# 3. 发送（正文带毫秒时间戳，注意正文里别用双引号）
NOW=$(date '+%H:%M:%S.%N' | cut -c1-12)
MSG="[${1:-测试消息}] $NOW"
RESP=$(curl -s --max-time 10 -X POST "$API/posts" -H "$AUTH" -H "$UA" \
    -H 'Content-Type: application/json' \
    -d "{\"channel_id\":\"$DM_ID\",\"message\":\"$MSG\"}")
CREATE_AT=$(echo "$RESP" | grep -o '"create_at":[0-9]*' | cut -d: -f2)
if [ -z "$CREATE_AT" ] || [ "$CREATE_AT" = "0" ]; then echo "✗ 发送失败: ${RESP:0:200}"; exit 1; fi
echo "✓ 已发送: $MSG"
echo "  post_id=$(echo "$RESP" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
echo "  服务器时间: $(date -d @$((CREATE_AT/1000)) '+%H:%M:%S.%3N' 2>/dev/null) (create_at=$CREATE_AT)"
echo "  对照手机通知/logcat 时间戳即可算出延迟"
