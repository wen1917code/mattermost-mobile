package com.wen.struggle.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / OTA 更新完成 / 快速开机 自启接收器。
 * PACKAGE_REPLACED 确保覆盖安装新版本后服务立即复活，不必等开机或手动打开。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWEREDON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.w("KeepAlive", "BootReceiver 收到 ${intent.action}，拉起保活服务")
                KeepAliveService.start(context)
            }
        }
    }
}
