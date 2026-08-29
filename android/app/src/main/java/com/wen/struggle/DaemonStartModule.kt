package com.wen.struggle

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.wen.struggle.services.KeepAliveActivity
import com.wen.struggle.services.MainService

class DaemonStartModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val GATE_MS = 5000L
        private var lastKeepAliveTime = 0L
        @Volatile var isAppInForeground = true
    }

    override fun getName(): String = "DaemonStartModule"

    @ReactMethod
    fun startDaemon() {
        try {
            val mainIntent = Intent(reactContext, MainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(mainIntent)
            } else {
                reactContext.startService(mainIntent)
            }
        } catch (e: Exception) {
            Log.e("DaemonGuard", "Failed to start MainService: ${e.message}", e)
        }
    }

    @ReactMethod
    fun setForeground(p0: Boolean) {
        isAppInForeground = p0
    }

    @ReactMethod
    fun showKeepAlive() {
        if (isAppInForeground) return
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveTime < GATE_MS) return
        lastKeepAliveTime = now
        try {
            val intent = Intent(reactContext, KeepAliveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DaemonGuard", "KeepAliveActivity failed: ${e.message}", e)
        }
    }

    @ReactMethod
    fun openBatterySettings() {
        Log.w("DaemonGuard", "openBatterySettings() called")
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${reactContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DaemonGuard", "openBatterySettings failed: ${e.message}", e)
        }
    }
}
