package com.porsche.aaos.platform.telemetry.collector.system

import android.os.Environment
import android.os.StatFs
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class StorageCollector @Inject constructor(
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Storage"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting storage monitoring (${POLL_INTERVAL_MS / 60_000}min interval)")

        // Emit immediately on startup
        emitStorageUsage()

        while (running && coroutineContext.isActive) {
            delay(POLL_INTERVAL_MS)
            emitStorageUsage()
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    private fun emitStorageUsage() {
        val dataStats = StatFs(Environment.getDataDirectory().path)
        val totalBytes = dataStats.totalBytes
        val availableBytes = dataStats.availableBytes
        val usedBytes = totalBytes - availableBytes

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Storage_Usage",
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "totalBytes" to totalBytes,
                        "usedBytes" to usedBytes,
                        "availableBytes" to availableBytes,
                        "usagePercent" to if (totalBytes > 0) {
                            (usedBytes * 100.0 / totalBytes)
                        } else {
                            0.0
                        },
                    ),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "StorageCollector"
        private const val POLL_INTERVAL_MS = 600_000L // 10 minutes
    }
}
