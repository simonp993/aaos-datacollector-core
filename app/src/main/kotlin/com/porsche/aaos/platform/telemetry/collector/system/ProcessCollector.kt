package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Collects running processes for ALL Android users using `ps -A`.
 *
 * ActivityManager.getRunningAppProcesses() is user-isolated — it only returns processes
 * for the calling user (user 0). To see user 10 processes (e.g. mapbox), we parse `ps -A`
 * output which shows all processes system-wide.
 *
 * Emits Process_Snapshot only when the set of running app PIDs changes (delta-based).
 * Payload uses sampleSchema format with compact per-process arrays.
 */
class ProcessCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Process"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Previous snapshot: pid → processName
    private var previousProcesses = emptyMap<Int, String>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting process monitoring (${POLL_INTERVAL_MS / 1_000}s interval, cross-user via ps -A)")

        // Initial stagger to avoid all collectors flushing at the same instant
        delay(STAGGER_DELAY_MS)

        while (running && coroutineContext.isActive) {
            val current = parseRunningProcesses()
            val currentPids = current.keys

            if (currentPids != previousProcesses.keys) {
                val addedPids = currentPids - previousProcesses.keys
                val removedPids = previousProcesses.keys - currentPids

                // Emit compact process list
                val processList = current.map { (pid, info) ->
                    listOf(pid, info.first, info.second)
                }

                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Process_Snapshot",
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "count" to current.size,
                                "added" to addedPids.map { pid ->
                                    listOf(pid, current[pid]?.first)
                                },
                                "removed" to removedPids.map { pid ->
                                    listOf(pid, previousProcesses[pid])
                                },
                                "sampleSchema" to listOf("pid", "processName", "user"),
                                "processes" to processList,
                            ),
                        ),
                    ),
                )

                previousProcesses = current.mapValues { it.value.first }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    /**
     * Parses `dumpsys activity processes` for ALL app processes across all users.
     * Returns map of pid → Pair(processName, user).
     *
     * Format: *APP* UID {uid} ProcessRecord{{hash} {pid}:{processName}/{userTag}}
     * Example: *APP* UID 1010187 ProcessRecord{abc123 2626:com.mapbox.porsche/u10a187}
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseRunningProcesses(): Map<Int, Pair<String, String>> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "processes"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val result = mutableMapOf<Int, Pair<String, String>>()
            for (line in output.lineSequence()) {
                if (!line.contains("*APP*")) continue
                val match = PROCESS_RECORD_PATTERN.find(line) ?: continue
                val pid = match.groupValues[1].toIntOrNull() ?: continue
                val name = match.groupValues[2]
                val user = match.groupValues[3]
                result[pid] = Pair(name, user)
            }
            result
        } catch (e: Exception) {
            logger.e(TAG, "Failed to parse dumpsys activity processes: ${e.message}")
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "ProcessCollector"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 7_000L // Offset from other 60s collectors
        // Matches: ProcessRecord{hash pid:processName/userTag}
        private val PROCESS_RECORD_PATTERN = Regex("""\{[a-f0-9]+ (\d+):([^/]+)/(\S+)\}""")
    }
}
