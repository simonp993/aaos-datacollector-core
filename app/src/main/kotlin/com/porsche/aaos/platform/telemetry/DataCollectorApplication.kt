package com.porsche.aaos.platform.telemetry

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DataCollectorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DataCollectorApplication.onCreate() — initializing telemetry service")
        try {
            DataCollectorService.start(this)
            Log.i(TAG, "✓ DataCollectorService started from Application.onCreate()")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to start DataCollectorService from Application", e)
        }
    }

    companion object {
        private const val TAG = "DataCollector:App"
    }
}
