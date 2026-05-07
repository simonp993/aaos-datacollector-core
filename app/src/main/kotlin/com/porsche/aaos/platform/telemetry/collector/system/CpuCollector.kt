package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Collects system-wide CPU usage by reading /proc/stat.
 *
 * Emits CPU_Usage heartbeat events with per-sample CPU utilisation percentage
 * computed from idle/total jiffies delta between two consecutive reads.
 * Also includes per-core breakdown count and system load average from /proc/loadavg.
 */
class CpuCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Cpu"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Previous /proc/stat snapshot for delta computation
    private var prevIdle = 0L
    private var prevTotal = 0L

    private val samples = mutableListOf<List<Any>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting CPU monitoring (${SAMPLE_INTERVAL_MS / 1_000}s sample, ${FLUSH_INTERVAL_MS / 1_000}s flush)")

        delay(STAGGER_DELAY_MS)

        // Seed the first read so the first delta is valid
        readCpuJiffies()?.let { (idle, total) ->
            prevIdle = idle
            prevTotal = total
        }

        while (running && coroutineContext.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            collectSample()

            if (samples.size >= SAMPLES_PER_BATCH) {
                flush()
            }
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectSample() {
        try {
            val jiffies = readCpuJiffies() ?: return

            val idleDelta = jiffies.first - prevIdle
            val totalDelta = jiffies.second - prevTotal
            prevIdle = jiffies.first
            prevTotal = jiffies.second

            val usagePct = if (totalDelta > 0) {
                ((totalDelta - idleDelta).toDouble() / totalDelta * 100).coerceIn(0.0, 100.0)
            } else {
                0.0
            }

            val loadAvg = readLoadAverage()
            val coreCount = Runtime.getRuntime().availableProcessors()

            synchronized(samples) {
                samples.add(
                    listOf(
                        System.currentTimeMillis(),
                        "%.1f".format(usagePct).toDouble(),
                        coreCount,
                        loadAvg,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to collect CPU sample: ${e.message}")
        }
    }

    private fun flush() {
        synchronized(samples) {
            if (samples.isEmpty()) return
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to "CPU_Usage",
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "sampleSchema" to listOf(
                                "timestampMillis",
                                "usagePct",
                                "coreCount",
                                "loadAvg1min",
                            ),
                            "samples" to samples.toList(),
                        ),
                    ),
                ),
            )
            samples.clear()
        }
    }

    /**
     * Reads the first (aggregate) line of /proc/stat.
     * Format: `cpu  user nice system idle iowait irq softirq steal guest guest_nice`
     * Returns Pair(idle, total) jiffies or null on failure.
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun readCpuJiffies(): Pair<Long, Long>? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val parts = line.split("\\s+".toRegex()).drop(1) // drop "cpu" label
            if (parts.size < 4) return null

            val values = parts.mapNotNull { it.toLongOrNull() }
            val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L } // idle + iowait
            val total = values.sum()
            Pair(idle, total)
        } catch (e: Exception) {
            logger.e(TAG, "Failed to read /proc/stat: ${e.message}")
            null
        }
    }

    /**
     * Reads 1-minute load average from /proc/loadavg.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readLoadAverage(): Double {
        return try {
            val line = File("/proc/loadavg").readText().trim()
            line.split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    companion object {
        private const val TAG = "CpuCollector"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val SAMPLES_PER_BATCH = 12
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 3_000L
    }
}
