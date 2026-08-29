package com.wen.struggle.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log

class SyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?
    ) {
        Log.i("SyncAdapter", "onPerformSync triggered")
        // WebSocket connectivity is managed by the app's WebsocketManager.
        // The SyncAdapter simply ensures the system keeps us alive periodically.
        // Actual reconnection is handled by WebsocketManager's own retry logic.
    }
}
