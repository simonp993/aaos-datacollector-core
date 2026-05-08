package com.porsche.aaos.platform.telemetry

import android.app.Activity
import android.os.Bundle
import android.util.Log

class DebugStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!BuildConfig.DEBUG) {
            finish()
            return
        }

        try {
            Log.i(TAG, "DebugStartActivity launched, starting DataCollectorService")
            DataCollectorService.start(applicationContext)
            Log.i(TAG, "DataCollectorService.start() invoked from DebugStartActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DataCollectorService from DebugStartActivity", e)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "DataCollector:DebugStartActivity"
    }
}
