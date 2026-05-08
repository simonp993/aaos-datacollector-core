package com.porsche.aaos.platform.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive() called with action: ${intent.action}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            try {
                Log.i(TAG, "Boot completed signal received — attempting to start DataCollectorService")
                val startTime = System.currentTimeMillis()
                DataCollectorService.start(context)
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "DataCollectorService.start() completed successfully in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: Failed to start DataCollectorService", e)
                e.printStackTrace()
            }
        } else {
            Log.d(TAG, "Ignoring action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "DataCollector:BootReceiver"
    }
}
