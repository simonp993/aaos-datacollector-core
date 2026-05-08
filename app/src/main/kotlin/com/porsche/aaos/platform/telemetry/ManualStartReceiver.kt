package com.porsche.aaos.platform.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ManualStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_SERVICE_DEBUG) {
            return
        }

        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Ignoring manual start broadcast on non-debug build")
            return
        }

        try {
            Log.i(TAG, "Manual debug start broadcast received")
            DataCollectorService.start(context.applicationContext)
            Log.i(TAG, "DataCollectorService.start() invoked from ManualStartReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DataCollectorService from ManualStartReceiver", e)
        }
    }

    companion object {
        const val ACTION_START_SERVICE_DEBUG =
            "com.porsche.aaos.platform.telemetry.action.START_SERVICE_DEBUG"
        private const val TAG = "DataCollector:ManualStartReceiver"
    }
}
