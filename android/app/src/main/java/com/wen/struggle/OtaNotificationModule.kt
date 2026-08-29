package com.wen.struggle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class OtaNotificationModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "OtaNotificationModule"

    companion object {
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIF_ID = 9999
    }

    private val manager: NotificationManager
        get() = reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "更新下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
    }

    @ReactMethod
    fun showProgress(percent: Int) {
        val notifyIntent = reactContext.packageManager.getLaunchIntentForPackage(reactContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            reactContext, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(reactContext, CHANNEL_ID)
            .setContentTitle("正在下载更新")
            .setContentText("${percent}%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_test)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)

        manager.notify(NOTIF_ID, builder.build())
    }

    @ReactMethod
    fun dismiss() {
        manager.cancel(NOTIF_ID)
    }

    @ReactMethod
    fun showFailed() {
        val notifyIntent = reactContext.packageManager.getLaunchIntentForPackage(reactContext.packageName)
        val pendingIntent = PendingIntent.getActivity(
            reactContext, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(reactContext, CHANNEL_ID)
            .setContentTitle("更新失败")
            .setContentText("下载失败，请重试")
            .setOngoing(false)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_test)
            .setContentIntent(pendingIntent)

        manager.notify(NOTIF_ID, builder.build())
    }
}
