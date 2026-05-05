package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class ProcessCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Process"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting process monitoring")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        while (running && coroutineContext.isActive) {
            val processes = activityManager.runningAppProcesses ?: emptyList()
            val processList = processes.map { proc ->
                mapOf(
                    "pid" to proc.pid,
                    "processName" to proc.processName,
                    "importance" to proc.importance,
                    "pkgList" to proc.pkgList?.toList(),
                )
            }

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "Process_ListPolled",
                        "metadata" to mapOf(
                            "count" to processes.size,
                            "processes" to processList,
                        ),
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
        private const val TAG = "ProcessCollector"
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
