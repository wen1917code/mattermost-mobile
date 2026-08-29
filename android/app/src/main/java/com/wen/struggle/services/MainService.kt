package com.wen.struggle.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.wen.struggle.R
import androidx.core.app.NotificationCompat

class MainService : Service() {

    companion object {
        const val CHANNEL_ID = "daemon_channel"
        const val NOTIF_ID = 10001
        private const val RECONNECT_INTERVAL = 15 * 60 * 1000L
        const val GATE_MS = 5000L
        @Volatile var lastStartDaemon = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            Log.w("DaemonGuard", "Periodic reconnect check")
            startDaemon()
            handler.postDelayed(this, RECONNECT_INTERVAL)
        }
    }

    override fun onCreate() {
        Log.w("DaemonGuard", "MainService.onCreate() pid=${android.os.Process.myPid()}")
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        startDaemon()
        handler.postDelayed(reconnectRunnable, RECONNECT_INTERVAL)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w("DaemonGuard", "MainService.onDestroy()")
        handler.removeCallbacks(reconnectRunnable)
        startDaemon()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startDaemon()
        try {
            val aliveIntent = Intent(this, KeepAliveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(aliveIntent)
        } catch (e: Exception) {
            Log.e("DaemonGuard", "KeepAliveActivity failed: ${e.message}", e)
        }
    }

    private fun startDaemon() {
        val now = System.currentTimeMillis()
        if (now - MainService.lastStartDaemon < GATE_MS) return
        MainService.lastStartDaemon = now
        try {
            val intent = Intent(this, DaemonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("DaemonGuard", "MainService startDaemon failed: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "\u200B",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\u200B")
            .setContentText("\u200B")
            .setSmallIcon(R.drawable.ic_test)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
