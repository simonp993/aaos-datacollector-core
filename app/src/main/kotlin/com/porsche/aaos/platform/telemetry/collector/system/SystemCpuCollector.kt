package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SystemCpuCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "SystemCpu"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var perProcessJob: Job? = null

    private var prevUserJiffies = 0L
    private var prevSystemJiffies = 0L
    private var prevTimestamp = 0L

    private val loadSamples = mutableListOf<List<Any>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting system CPU monitoring")

        delay(STAGGER_DELAY_MS)

        readProcessCpuJiffies()?.let { (u, s) ->
            prevUserJiffies = u
            prevSystemJiffies = s
            prevTimestamp = System.currentTimeMillis()
        }

        perProcessJob = CoroutineScope(Dispatchers.IO).launch {
            delay(PER_PROCESS_STAGGER_MS)
            val perProcessSamples = mutableListOf<Map<String, Any>>()
            while (running && isActive) {
                collectPerProcess(perProcessSamples)
                if (perProcessSamples.size >= PER_PROCESS_BATCH) {
                    flushPerProcess(perProcessSamples)
                }
                delay(PER_PROCESS_INTERVAL_MS)
            }
        }

        while (running && coroutineContext[Job]?.isActive != false) {
            collectCpuLoad()
            if (loadSamples.size >= LOAD_SAMPLES_PER_BATCH) {
                flushCpuLoad()
            }
            delay(LOAD_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        perProcessJob?.cancel()
        perProcessJob = null
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectCpuLoad() {
        try {
            val now = System.currentTimeMillis()
            val ownCpuPct = computeOwnCpuPct(now)
            synchronized(loadSamples) {
                loadSamples.add(listOf(now, ownCpuPct))
            }
        } catch (e: Exception) {
            logger.e(TAG, "CpuLoad sample failed: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun flushCpuLoad() {
        try {
            // Limitation: systemSummary (totalCpuMs, idlePct, iowaitPct, contextSwitches,
            // memPressurePct) from CarWatchdog is not available — SELinux denies
            // platform_app access to carwatchdogd_service.

            synchronized(loadSamples) {
                if (loadSamples.isEmpty()) return
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to ACTION_CPU_LOAD,
                            "trigger" to "heartbeat",
                            "metadata" to mapOf(
                                "coreCount" to Runtime.getRuntime().availableProcessors(),
                                "sampleSchema" to LOAD_SCHEMA,
                                "samples" to loadSamples.toList(),
                            ),
                        ),
                    ),
                )
                loadSamples.clear()
            }
        } catch (e: Exception) {
            logger.e(TAG, "flushCpuLoad failed: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectPerProcess(buffer: MutableList<Map<String, Any>>) {
        try {
            val output = execCommand("dumpsys", "cpuinfo") ?: return
            val now = System.currentTimeMillis()
            val processes = mutableListOf<List<Any>>()
            var totalPct = 0.0
            var userPct = 0.0
            var kernelPct = 0.0
            var iowaitPct = 0.0
            var irqPct = 0.0

            for (line in output.lineSequence()) {
                val trimmed = line.trim()

                val procMatch = PROCESS_LINE_PATTERN.find(trimmed)
                if (procMatch != null) {
                    val cpuTotal = procMatch.groupValues[1].toDoubleOrNull() ?: continue
                    val pid = procMatch.groupValues[2].toIntOrNull() ?: continue
                    val procName = procMatch.groupValues[3]
                    val userVal = procMatch.groupValues[4].toDoubleOrNull() ?: 0.0
                    val kernelVal = procMatch.groupValues[5].toDoubleOrNull() ?: 0.0
                    val faults = FAULTS_PATTERN.find(trimmed)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    processes.add(
                        listOf(pid, procName, cpuTotal, userVal, kernelVal, faults),
                    )
                    continue
                }

                val totalMatch = TOTAL_LINE_PATTERN.find(trimmed)
                if (totalMatch != null) {
                    totalPct = totalMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    userPct = totalMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                    kernelPct = totalMatch.groupValues[3].toDoubleOrNull() ?: 0.0
                    iowaitPct = IOWAIT_PATTERN.find(trimmed)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    irqPct = IRQ_PATTERN.find(trimmed)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                }
            }

            if (processes.isEmpty()) return

            buffer.add(
                mapOf(
                    "ts" to now,
                    "summary" to listOf(totalPct, userPct, kernelPct, iowaitPct, irqPct),
                    "processes" to processes,
                ),
            )
        } catch (e: Exception) {
            logger.e(TAG, "CpuPerProcess sample failed: ${e.message}")
        }
    }

    private fun flushPerProcess(buffer: MutableList<Map<String, Any>>) {
        if (buffer.isEmpty()) return
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_CPU_PER_PROCESS,
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "summarySchema" to listOf(
                            "totalPct", "userPct", "kernelPct", "iowaitPct", "irqPct",
                        ),
                        "processSchema" to listOf(
                            "pid", "name", "cpuPct", "userPct", "kernelPct", "minorFaults",
                        ),
                        "snapshots" to buffer.toList(),
                    ),
                ),
            ),
        )
        buffer.clear()
    }

    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun computeOwnCpuPct(now: Long): Double {
        return try {
            val jiffies = readProcessCpuJiffies() ?: return 0.0
            val userDelta = jiffies.first - prevUserJiffies
            val systemDelta = jiffies.second - prevSystemJiffies
            val timeDeltaMs = now - prevTimestamp
            prevUserJiffies = jiffies.first
            prevSystemJiffies = jiffies.second
            prevTimestamp = now

            if (timeDeltaMs > 0) {
                val totalJiffies = userDelta + systemDelta
                val elapsedJiffies = timeDeltaMs / JIFFY_MS
                if (elapsedJiffies > 0) {
                    "%.1f".format(
                        (totalJiffies.toDouble() / elapsedJiffies * PERCENT)
                            .coerceIn(0.0, PERCENT),
                    ).toDouble()
                } else {
                    0.0
                }
            } else {
                0.0
            }
        } catch (e: Exception) {
            0.0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readProcessCpuJiffies(): Pair<Long, Long>? {
        return try {
            val stat = File("/proc/self/stat").readText()
            val afterCmd = stat.substringAfterLast(") ")
            val fields = afterCmd.split(" ")
            val utime = fields.getOrNull(UTIME_INDEX)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(STIME_INDEX)?.toLongOrNull() ?: return null
            Pair(utime, stime)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execCommand(vararg args: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(args)
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            logger.e(TAG, "exec failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SystemCpuCollector"
        private const val STAGGER_DELAY_MS = 2_000L
        private const val LOAD_INTERVAL_MS = 1_000L
        private const val LOAD_SAMPLES_PER_BATCH = 60
        private const val PER_PROCESS_INTERVAL_MS = 60_000L
        private const val PER_PROCESS_STAGGER_MS = 5_000L
        private const val PER_PROCESS_BATCH = 1
        private const val JIFFY_MS = 10L
        private const val PERCENT = 100.0
        private const val UTIME_INDEX = 11
        private const val STIME_INDEX = 12

        private const val ACTION_CPU_LOAD = "System_CpuLoad"
        private const val ACTION_CPU_PER_PROCESS = "System_CpuPerProcess"

        private val LOAD_SCHEMA = listOf("ts", "ownCpuPct")

        private val PROCESS_LINE_PATTERN = Regex(
            """^(\d+(?:\.\d+)?)%\s+(\d+)/([^:]+):\s+(\d+(?:\.\d+)?)%\s+user\s+\+\s+(\d+(?:\.\d+)?)%\s+kernel""",
        )

        private val TOTAL_LINE_PATTERN = Regex(
            """^(\d+(?:\.\d+)?)%\s+TOTAL:\s+(\d+(?:\.\d+)?)%\s+user\s+\+\s+(\d+(?:\.\d+)?)%\s+kernel""",
        )

        private val IOWAIT_PATTERN = Regex("""(\d+(?:\.\d+)?)%\s+iowait""")
        private val IRQ_PATTERN = Regex("""(\d+(?:\.\d+)?)%\s+irq""")
        private val FAULTS_PATTERN = Regex("""faults:\s+(\d+)\s+minor""")
    }
}
