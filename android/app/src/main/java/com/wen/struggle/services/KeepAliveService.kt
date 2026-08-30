package com.wen.struggle.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wen.struggle.R
import com.wen.struggle.websocket.MattermostWebSocket

/**
 * 单一保活前台服务（主进程，不再拆 :main/:daemon 子进程）。
 *
 * 架构：本服务既是「被系统保活的对象」也是「通知的载体」——原生 WebSocket 直接跑在
 * 服务里，系统重启服务即恢复通知能力（重启/OTA 后无需打开 App）。
 *
 * 接管策略（evaluate）：
 *   App 前台               → 原生 WS 关闭，由 JS WebSocket 负责；
 *   App 后台 && JS 活着    → 原生 WS 关闭（JS 心跳在 90s 内且 JS 报告连接正常）；
 *   App 后台 && JS 失联    → 原生 WS 启动（进程被杀 / JS 断连 / 重启未开 App）。
 * 一旦后台接管，保持到回前台为止，避免与 JS 恢复瞬间双重通知。
 *
 * 唤醒体系：AlarmManager 健康检查（后台 2 分钟/前台 5 分钟）+ BootReceiver +
 * PACKAGE_REPLACED + WorkManager 兜底，任一存活都能拉起本服务。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        const val CHANNEL_KEEPALIVE = "daemon_channel"
        const val CHANNEL_MESSAGES = "messages"
        const val NOTIF_ID = 10001
        const val SESSION_EXPIRED_NOTIF_ID = 9001
        const val PREFS = "keepalive_prefs"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER = "server_url"
        const val KEY_USER_ID = "user_id"
        const val KEY_FOREGROUND = "app_foreground"
        const val KEY_LAST_JS_HEARTBEAT = "last_js_heartbeat"
        const val KEY_JS_WS_CONNECTED = "js_ws_connected"
        const val ACTION_CHECK = "com.wen.struggle.action.CHECK"
        const val ACTION_TOKEN_UPDATED = "com.wen.struggle.action.TOKEN_UPDATED"
        const val ACTION_FOREGROUND = "com.wen.struggle.action.FOREGROUND"
        const val EXTRA_FOREGROUND = "foreground"
        const val ALARM_REQUEST_CODE = 1001
        private const val JS_HEARTBEAT_TIMEOUT = 90_000L
        private const val ALARM_BACKGROUND = 2 * 60_000L
        private const val ALARM_FOREGROUND = 5 * 60_000L
        const val DEFAULT_SERVER_URL = "https://sg.ant.wenzi.uno"

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}")
            }
        }

        /** 健康检查闹钟：服务死掉后由 AlarmReceiver 借此拉起。 */
        fun scheduleAlarm(context: Context, delayMs: Long) {
            try {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    context,
                    ALARM_REQUEST_CODE,
                    Intent(context, com.wen.struggle.receivers.AlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val at = System.currentTimeMillis() + delayMs
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
                try {
                    if (canExact) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    }
                } catch (e: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleAlarm failed: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webSocket: MattermostWebSocket? = null
    @Volatile private var nativeActive = false

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "网络变化，触发健康检查")
            evaluate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "KeepAliveService.onCreate pid=${android.os.Process.myPid()}")
        createChannels()
        startInForeground()
        // 进程是新拉起的：JS 与本服务同进程，进程死过则心跳必已失效，
        // 旧心跳会让原生白等 90 秒造成通知空窗——立即清零，让原生马上接管
        prefs(this).edit()
            .putLong(KEY_LAST_JS_HEARTBEAT, 0L)
            .putBoolean(KEY_JS_WS_CONNECTED, false)
            .apply()
        try {
            registerReceiver(
                connectivityReceiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
            )
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed: ${e.message}")
        }
        evaluate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHECK -> {
                Log.i(TAG, "健康检查")
                evaluate()
            }
            ACTION_TOKEN_UPDATED -> {
                Log.i(TAG, "token 已更新，重建原生 WebSocket")
                val active = nativeActive
                stopNativeWs()
                if (active) {
                    evaluate()
                }
            }
            ACTION_FOREGROUND -> {
                val fg = intent.getBooleanExtra(EXTRA_FOREGROUND, false)
                prefs(this).edit().putBoolean(KEY_FOREGROUND, fg).apply()
                Log.i(TAG, "前后台切换: foreground=$fg")
                evaluate()
            }
            else -> evaluate()
        }
        scheduleAlarm(this, nextAlarmDelay())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "onTaskRemoved，保持服务运行")
        scheduleAlarm(this, 60_000L)
    }

    override fun onDestroy() {
        Log.w(TAG, "KeepAliveService.onDestroy，安排自复活闹钟")
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (_: Exception) {
        }
        stopNativeWs()
        // 系统主动重启 sticky 服务之外的兜底：60s 后闹钟尝试拉起
        scheduleAlarm(this, 60_000L)
        super.onDestroy()
    }

    /**
     * 接管策略核心：后台 && (JS 心跳消失 || JS 自报断连) && 有 token → 原生接管。
     * 接管后不因 JS 恢复心跳而让位（避免交接窗口双通知），回前台才释放。
     */
    private fun evaluate() {
        val p = prefs(this)
        val fg = p.getBoolean(KEY_FOREGROUND, false)
        val token = p.getString(KEY_TOKEN, null)
        val lastBeat = p.getLong(KEY_LAST_JS_HEARTBEAT, 0L)
        val jsWsOk = p.getBoolean(KEY_JS_WS_CONNECTED, false)
        val heartbeatAlive =
            lastBeat > 0 && System.currentTimeMillis() - lastBeat < JS_HEARTBEAT_TIMEOUT
        val shouldRunNative = !fg && token != null && (!heartbeatAlive || !jsWsOk)

        Log.i(
            TAG,
            "evaluate: fg=$fg heartbeatAlive=$heartbeatAlive jsWsOk=$jsWsOk hasToken=${token != null} " +
                "nativeActive=$nativeActive -> shouldRunNative=$shouldRunNative",
        )

        if (shouldRunNative) {
            startNativeWs()
        } else {
            // 前台 或 JS 恢复健康：原生让位（JS 恢复的头一个心跳周期内理论上可能
            // 双通知，权衡后选择可靠性优先——JS 断连期间原生必须顶上）
            stopNativeWs()
        }
    }

    private fun startNativeWs() {
        if (webSocket != null) return
        val p = prefs(this)
        val token = p.getString(KEY_TOKEN, null) ?: return
        val serverUrl = p.getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL
        webSocket = MattermostWebSocket(serverUrl, token, object : MattermostWebSocket.Listener {
            override fun onPosted(postId: String, userId: String, title: String, body: String) {
                val myUserId = prefs(this@KeepAliveService).getString(KEY_USER_ID, null)
                if (myUserId != null && userId == myUserId) {
                    return
                }
                handler.post { postMessageNotification(postId, title, body) }
            }

            override fun onAuthError() {
                handler.post {
                    postSessionExpiredNotification()
                    stopNativeWs()
                }
            }

            override fun onStateChanged(connected: Boolean) {
                Log.i(TAG, "原生 WebSocket 状态: ${if (connected) "已连接" else "断开"}")
            }
        }).also { ws ->
            nativeActive = true
            ws.connect()
        }
    }

    private fun stopNativeWs() {
        webSocket?.shutdown()
        webSocket = null
        nativeActive = false
    }

    private fun postMessageNotification(postId: String, title: String, body: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_notification)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(postId.hashCode(), notification)
    }

    private fun postSessionExpiredNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle("StruGGle 登录已过期")
            .setContentText("请打开 App 重新登录以继续接收消息")
            .setSmallIcon(R.mipmap.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(SESSION_EXPIRED_NOTIF_ID, notification)
    }

    private fun nextAlarmDelay(): Long {
        val fg = prefs(this).getBoolean(KEY_FOREGROUND, false)
        return if (fg) ALARM_FOREGROUND else ALARM_BACKGROUND
    }

    private fun startInForeground() {
        val notification = buildKeepAliveNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildKeepAliveNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_KEEPALIVE)
            .setContentTitle("\u200B")
            .setContentText("\u200B")
            .setSmallIcon(R.drawable.ic_test)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        // 静默保活渠道（零宽字符名称 + 最低重要性）
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEPALIVE, "\u200B", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        // 消息渠道（MainApplication 也创建过，幂等）
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "新消息", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
                setBypassDnd(true)
                description = "消息推送通知"
            },
        )
    }
}
