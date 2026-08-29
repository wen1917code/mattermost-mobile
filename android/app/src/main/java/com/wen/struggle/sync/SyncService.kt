package com.wen.struggle.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SyncService : Service() {

    private var syncAdapter: SyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        syncAdapter = SyncAdapter(applicationContext, true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return syncAdapter?.syncAdapterBinder
    }
}
