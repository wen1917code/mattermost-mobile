package com.wen.struggle.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wen.struggle.R
import androidx.core.app.NotificationCompat

class DaemonService : Service() {

    override fun onCreate() {
        Log.w("DaemonGuard", "DaemonService.onCreate() pid=${android.os.Process.myPid()}")
        super.onCreate()
        createNotificationChannel()
        startForeground(MainService.NOTIF_ID + 1, buildNotification())
        startMain()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.w("DaemonGuard", "DaemonService.onDestroy()")
        startMain()
        super.onDestroy()
    }

    private fun startMain() {
        val now = System.currentTimeMillis()
        if (now - MainService.lastStartDaemon < MainService.GATE_MS) return
        MainService.lastStartDaemon = now
        try {
            val intent = Intent(this, MainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("DaemonGuard", "DaemonService startMain failed: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MainService.CHANNEL_ID,
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
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MainService.CHANNEL_ID)
            .setContentTitle("\u200B")
            .setContentText("\u200B")
            .setSmallIcon(R.drawable.ic_test)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
