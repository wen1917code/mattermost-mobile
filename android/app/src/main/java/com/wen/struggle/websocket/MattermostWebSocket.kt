package com.wen.struggle.websocket

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Mattermost WebSocket 原生客户端（协议最小子集）。
 *
 * 认证：连接成功后发送 authentication_challenge(token)。
 * 保活：30s 应用层 ping，90s 未收到 pong 主动断开重连；另有 OkHttp 协议层 ping 兜底。
 * 通知：只关心 posted 事件，解析出标题（频道显示名）与正文（消息）交给 Listener。
 * 重连：指数退避 3s 起步（失败超过 7 次按次数放大），上限 5 分钟，带随机抖动。
 *
 * 仅在 JS 侧 WebSocket 不可用时（App 在后台且 JS 心跳消失/断连、或重启后未打开过
 * App）由 KeepAliveService 创建启用；App 回前台即销毁，由 JS 侧接管。
 */
class MattermostWebSocket(
    private val serverUrl: String,
    private val token: String,
    private val listener: Listener,
) {

    interface Listener {
        /** 收到新消息（含发送者 user_id，用于过滤自己发的消息）。title 通常为频道显示名。 */
        fun onPosted(postId: String, userId: String, title: String, body: String)

        /** token 失效（服务端明确拒绝认证），需停止重连直到 token 更新。 */
        fun onAuthError()

        /** 连接状态变化（主线程回调）。 */
        fun onStateChanged(connected: Boolean)
    }

    companion object {
        private const val TAG = "KeepAliveWS"
        private const val PING_INTERVAL = 30_000L
        private const val PONG_TIMEOUT = 90_000L
        private const val MIN_RETRY = 3_000L
        private const val MAX_RETRY = 5 * 60_000L
        private const val FAST_BACKOFF_FAILS = 7
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val stopped = AtomicBoolean(false)
    private var reqSeq = 1
    private var failCount = 0
    private var lastPongAt = 0L
    @Volatile private var authFailed = false

    private val pingRunnable = object : Runnable {
        override fun run() {
            val ws = webSocket ?: return
            val now = System.currentTimeMillis()
            if (lastPongAt != 0L && now - lastPongAt > PONG_TIMEOUT) {
                Log.w(TAG, "应用层 pong 超时，主动断开重连")
                ws.cancel()
                return
            }
            send(ws, JSONObject().apply {
                put("action", "ping")
                put("seq", reqSeq++)
            })
            handler.postDelayed(this, PING_INTERVAL)
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (stopped.get()) {
                ws.close(1000, null)
                return
            }
            Log.i(TAG, "已连接 $serverUrl，发送认证")
            lastPongAt = System.currentTimeMillis()
            send(ws, JSONObject().apply {
                put("action", "authentication_challenge")
                put("seq", reqSeq++)
                put("data", JSONObject().put("token", token))
            })
            handler.removeCallbacks(pingRunnable)
            handler.postDelayed(pingRunnable, PING_INTERVAL)
            failCount = 0
            listener.onStateChanged(true)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (stopped.get()) return
            handleMessage(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (stopped.get()) return
            Log.w(TAG, "连接失败: ${t.message}")
            listener.onStateChanged(false)
            scheduleReconnect()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (stopped.get()) return
            listener.onStateChanged(false)
            scheduleReconnect()
        }
    }

    fun connect() {
        if (stopped.get() || authFailed || webSocket != null) return
        val wsUrl = (serverUrl.removeSuffix("/") + "/api/v4/websocket")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            // 服务端 nginx 有 UA 白名单，未知 UA 会被 403
            .header("User-Agent", "Mattermost Mobile")
            .header("Origin", serverUrl.removeSuffix("/"))
            .build()
        Log.i(TAG, "连接 $wsUrl")
        webSocket = client.newWebSocket(request, socketListener)
    }

    /** 永久停用本实例（App 回前台 / token 失效时调用，之后不可复用）。 */
    fun shutdown() {
        stopped.set(true)
        handler.removeCallbacksAndMessages(null)
        try {
            webSocket?.close(1000, null)
        } catch (_: Exception) {
        }
        webSocket = null
        listener.onStateChanged(false)
    }

    val isRunning: Boolean get() = !stopped.get() && webSocket != null

    private fun handleMessage(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        // 对请求的应答（认证结果 / pong）
        if (msg.has("seq_reply")) {
            val err = msg.optJSONObject("error")
            if (err != null) {
                val id = err.optString("id", "")
                if (id.contains("not_authenticated") ||
                    id.contains("invalid_session") ||
                    id.contains("api.session") ||
                    id.contains("token")
                ) {
                    Log.w(TAG, "认证被拒: $id")
                    authFailed = true
                    listener.onAuthError()
                    shutdown()
                    return
                }
                Log.w(TAG, "WebSocket 应答错误: $id ${err.optString("message")}")
            }
            if (msg.optJSONObject("data")?.optString("text") == "pong") {
                lastPongAt = System.currentTimeMillis()
            }
            return
        }

        if (msg.optString("event") == "posted") {
            val data = msg.optJSONObject("data") ?: return
            val post = try {
                JSONObject(data.optString("post"))
            } catch (e: Exception) {
                return
            }
            val title = data.optString("channel_display_name")
                .ifEmpty {
                    data.optString("sender_name").removePrefix("@")
                }
                .ifEmpty { "新消息" }
            listener.onPosted(
                post.optString("id"),
                post.optString("user_id"),
                title,
                post.optString("message").ifEmpty { "收到一条新消息" },
            )
        }
    }

    private fun scheduleReconnect() {
        webSocket = null
        handler.removeCallbacks(pingRunnable)
        if (stopped.get() || authFailed) return
        failCount++
        val base = if (failCount > FAST_BACKOFF_FAILS) {
            minOf(MIN_RETRY * failCount, MAX_RETRY)
        } else {
            MIN_RETRY
        }
        val delay = base + Random.nextLong(0, base / 3)
        Log.i(TAG, "${delay}ms 后重连（第 $failCount 次失败）")
        handler.postDelayed({ connect() }, delay)
    }

    private fun send(ws: WebSocket, payload: JSONObject) {
        try {
            ws.send(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "发送失败: ${e.message}")
        }
    }
}
