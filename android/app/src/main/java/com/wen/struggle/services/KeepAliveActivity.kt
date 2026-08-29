package com.wen.struggle.services

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

class KeepAliveActivity : Activity() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.w("KeepAlive", "KeepAliveActivity.onCreate()")
        super.onCreate(savedInstanceState)

        // Set window to 1x1 pixel
        window.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setGravity(Gravity.START or Gravity.TOP)
            val params = attributes
            params.x = 0
            params.y = 0
            params.width = 1
            params.height = 1
            attributes = params
        }

        // Move off screen immediately to avoid focus stealing
        moveTaskToBack(true)

        // Register screen-on receiver to auto-dismiss
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onDestroy() {
        Log.w("KeepAlive", "KeepAliveActivity.onDestroy()")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
