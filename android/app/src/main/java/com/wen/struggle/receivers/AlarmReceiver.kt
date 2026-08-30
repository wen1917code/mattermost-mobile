package com.wen.struggle.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wen.struggle.services.KeepAliveService

/**
 * 健康检查闹钟接收器：由 KeepAliveService.scheduleAlarm 周期触发。
 * 服务还活着 → onStartCommand 走 CHECK 逻辑重新评估策略；
 * 服务已死   → startForegroundService 将其复活（闹钟唤醒自带临时白名单，允许启动前台服务）。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("KeepAlive", "AlarmReceiver 触发健康检查")
        KeepAliveService.start(context)
    }
}
