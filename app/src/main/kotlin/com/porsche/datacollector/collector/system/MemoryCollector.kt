package com.porsche.datacollector.collector.system

import android.app.ActivityManager
import android.content.Context
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class MemoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Memory"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting memory monitoring")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        while (running && coroutineContext.isActive) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            telemetry.send(
                TelemetryEvent(
                    eventId = "system.memory",
                    payload = mapOf(
                        "totalMem" to memInfo.totalMem,
                        "availMem" to memInfo.availMem,
                        "lowMemory" to memInfo.lowMemory,
                        "threshold" to memInfo.threshold,
                    ),
                ),
            )

            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "MemoryCollector"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
