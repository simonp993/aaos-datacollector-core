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

    private var previousPids = emptySet<Int>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting process monitoring (${POLL_INTERVAL_MS / 1_000}s interval, change-based)")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        while (running && coroutineContext.isActive) {
            val processes = activityManager.runningAppProcesses ?: emptyList()
            val currentPids = processes.map { it.pid }.toSet()

            if (currentPids != previousPids) {
                val added = currentPids - previousPids
                val removed = previousPids - currentPids

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
                            "actionName" to "Process_Snapshot",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "count" to processes.size,
                                "addedPids" to added.toList(),
                                "removedPids" to removed.toList(),
                                "processes" to processList,
                            ),
                        ),
                    ),
                )
                previousPids = currentPids
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "ProcessCollector"
        private const val POLL_INTERVAL_MS = 60_000L
    }
}
