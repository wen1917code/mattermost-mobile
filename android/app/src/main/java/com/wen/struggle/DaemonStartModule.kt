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
import com.wen.struggle.services.KeepAliveService

/**
 * JS ↔ 原生保活桥。
 *
 * startDaemon()    启动保活前台服务（兼容旧接口名）
 * setForeground()  通知原生当前 App 前后台状态
 * saveToken()      登录/启动时写入 token，供原生 WebSocket 认证
 * heartbeat()      JS 每 30s 上报一次 WebSocket 健康状态；原生在后台发现心跳消失
 *                  或 JS 自报断连时接管推送，JS 活着时绝不重复通知
 */
class DaemonStartModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "KeepAlive"
        @Volatile var isAppInForeground = true
    }

    override fun getName(): String = "DaemonStartModule"

    @ReactMethod
    fun startDaemon() {
        KeepAliveService.start(reactContext)
    }

    @ReactMethod
    fun setForeground(foreground: Boolean) {
        isAppInForeground = foreground
        try {
            val intent = Intent(reactContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_FOREGROUND
                putExtra(KeepAliveService.EXTRA_FOREGROUND, foreground)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setForeground failed: ${e.message}")
        }
    }

    @ReactMethod
    fun saveToken(serverUrl: String, token: String, userId: String) {
        try {
            KeepAliveService.prefs(reactContext).edit()
                .putString(KeepAliveService.KEY_SERVER, serverUrl)
                .putString(KeepAliveService.KEY_TOKEN, token)
                .putString(KeepAliveService.KEY_USER_ID, userId)
                .apply()
            Log.i(TAG, "token 已保存（serverUrl=$serverUrl userId=$userId）")
            val intent = Intent(reactContext, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_TOKEN_UPDATED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToken failed: ${e.message}")
        }
    }

    @ReactMethod
    fun heartbeat(wsConnected: Boolean) {
        try {
            KeepAliveService.prefs(reactContext).edit()
                .putLong(KeepAliveService.KEY_LAST_JS_HEARTBEAT, System.currentTimeMillis())
                .putBoolean(KeepAliveService.KEY_JS_WS_CONNECTED, wsConnected)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed: ${e.message}")
        }
    }

    @ReactMethod
    fun showKeepAlive() {
        // 1px Activity 现为可选手段（默认不再由 JS 触发），保留给特殊 ROM 场景
        if (isAppInForeground) return
        try {
            val intent = Intent(reactContext, KeepAliveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveActivity failed: ${e.message}")
        }
    }

    @ReactMethod
    fun openBatterySettings() {
        Log.w(TAG, "openBatterySettings() called")
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${reactContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openBatterySettings failed: ${e.message}")
        }
    }
}
