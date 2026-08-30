package com.wen.struggle

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wen.struggle.services.KeepAliveService

/**
 * WorkManager 兜底体检：部分国产 ROM 杀闹钟但放过 JobScheduler，
 * 与 AlarmManager 互为备份，每 15 分钟确认一次保活服务存活。
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        Log.i("KeepAlive", "KeepAliveWorker 体检")
        return try {
            KeepAliveService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("KeepAlive", "KeepAliveWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
