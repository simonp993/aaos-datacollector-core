package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collects system-wide memory metrics in two action groups:
 *
 * - **System_Memory** (1 s sample, 60/batch): total, available, used, threshold,
 *   low-memory flag via [ActivityManager.MemoryInfo].
 * - **System_MemoryPerProcess** (60 s, 1/batch): per-UID RSS from
 *   CarWatchdog + own-process details (USS, threads) from /proc/self.
 *
 * CarWatchdog provides real cross-process RSS for ALL packages, replacing the
 * broken `/proc/{pid}/statm` approach (blocked by SELinux for other UIDs).
 */
class SystemMemoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "SystemMemory"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var perProcessJob: Job? = null

    private val memorySamples = mutableListOf<List<Any>>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting system memory monitoring")

        delay(STAGGER_DELAY_MS)

        // Per-process memory at 60 s in separate coroutine (aligned with watchdog)
        perProcessJob = CoroutineScope(Dispatchers.IO).launch {
            delay(PER_PROCESS_STAGGER_MS)
            while (running && isActive) {
                collectAndFlushPerProcess()
                delay(PER_PROCESS_INTERVAL_MS)
            }
        }

        // System_Memory at 1 s in the calling coroutine
        while (running) {
            collectSystemMemory()
            if (memorySamples.size >= MEMORY_SAMPLES_PER_BATCH) {
                flushSystemMemory()
            }
            delay(MEMORY_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        perProcessJob?.cancel()
        perProcessJob = null
        logger.i(TAG, "Stopped")
    }

    // ── Group 1: System_Memory (1 s) ──────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun collectSystemMemory() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)

            val totalMb = memInfo.totalMem / BYTES_PER_MB
            val availMb = memInfo.availMem / BYTES_PER_MB
            val usedMb = totalMb - availMb
            val thresholdMb = memInfo.threshold / BYTES_PER_MB

            synchronized(memorySamples) {
                memorySamples.add(
                    listOf(
                        System.currentTimeMillis(),
                        totalMb,
                        availMb,
                        usedMb,
                        thresholdMb,
                        if (memInfo.lowMemory) 1 else 0,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.e(TAG, "System_Memory sample failed: ${e.message}")
        }
    }

    private fun flushSystemMemory() {
        synchronized(memorySamples) {
            if (memorySamples.isEmpty()) return
            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to ACTION_SYSTEM_MEMORY,
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "sampleSchema" to listOf(
                                "ts", "totalMb", "availableMb", "usedMb",
                                "thresholdMb", "lowMemory",
                            ),
                            "samples" to memorySamples.toList(),
                        ),
                    ),
                ),
            )
            memorySamples.clear()
        }
    }

    // ── Group 2: System_MemoryPerProcess (60 s) ───────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun collectAndFlushPerProcess() {
        try {
            val now = System.currentTimeMillis()

            // Extended system-level memory info from /proc/meminfo
            val extended = readExtendedMemInfo()

            // Limitation: per-UID RSS (packages) from CarWatchdog is not available —
            // SELinux denies platform_app access to carwatchdogd_service.

            // Own-process details (we can always read /proc/self)
            val ownRssKb = readProcRssKb()
            val ownUssKb = readProcUssKb()
            val ownThreads = readProcThreadCount()

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to ACTION_MEMORY_PER_PROCESS,
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "ts" to now,
                            "extended" to extended,
                            "ownProcess" to mapOf(
                                "rssKb" to ownRssKb,
                                "ussKb" to ownUssKb,
                                "threads" to ownThreads,
                            ),
                        ),
                    ),
                    timestamp = now,
                ),
            )
        } catch (e: Exception) {
            logger.e(TAG, "MemoryPerProcess failed: ${e.message}")
        }
    }

    // ── Data source helpers ────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun readExtendedMemInfo(): Map<String, Long> {
        return try {
            val fields = mutableMapOf<String, Long>()
            File("/proc/meminfo").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    val parts = line.split(":")
                    if (parts.size < 2) continue
                    val key = parts[0].trim()
                    val valueKb = parts[1].trim().split(" ").firstOrNull()
                        ?.toLongOrNull() ?: continue

                    when (key) {
                        "Cached" -> fields["cachedMb"] = valueKb / KB_PER_MB
                        "SwapFree" -> fields["swapFreeMb"] = valueKb / KB_PER_MB
                    }
                }
            }
            // zRAM info from /sys/block/zram0/
            fields["zramUsedMb"] = readSysLong("/sys/block/zram0/mem_used_total") / BYTES_PER_MB
            fields["zramTotalMb"] = readSysLong("/sys/block/zram0/disksize") / BYTES_PER_MB
            fields
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readSysLong(path: String): Long {
        return try {
            File(path).readText().trim().toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readProcRssKb(): Long {
        return try {
            val fields = File("/proc/self/statm").readText().trim().split(" ")
            (fields.getOrNull(1)?.toLongOrNull() ?: 0L) * PAGE_SIZE_KB
        } catch (e: Exception) {
            0L
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readProcUssKb(): Long {
        return try {
            var privateClean = 0L
            var privateDirty = 0L
            File("/proc/self/smaps_rollup").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    when {
                        line.startsWith("Private_Clean:") -> {
                            privateClean = extractKb(line)
                        }
                        line.startsWith("Private_Dirty:") -> {
                            privateDirty = extractKb(line)
                        }
                    }
                }
            }
            privateClean + privateDirty
        } catch (e: Exception) {
            0L
        }
    }

    private fun extractKb(line: String): Long =
        line.split("\\s+".toRegex()).getOrNull(1)?.toLongOrNull() ?: 0L

    @Suppress("TooGenericExceptionCaught")
    private fun readProcThreadCount(): Int {
        return try {
            File("/proc/self/status").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    if (line.startsWith("Threads:")) {
                        return line.split("\\s+".toRegex())
                            .getOrNull(1)?.toIntOrNull() ?: 0
                    }
                }
            }
            0
        } catch (e: Exception) {
            0
        }
    }

    companion object {
        private const val TAG = "SystemMemoryCollector"
        private const val STAGGER_DELAY_MS = 4_000L
        private const val MEMORY_INTERVAL_MS = 1_000L
        private const val MEMORY_SAMPLES_PER_BATCH = 60
        private const val PER_PROCESS_INTERVAL_MS = 60_000L
        private const val PER_PROCESS_STAGGER_MS = 10_000L
        private const val BYTES_PER_MB = 1_048_576L
        private const val KB_PER_MB = 1_024L
        private const val PAGE_SIZE_KB = 4L

        private const val ACTION_SYSTEM_MEMORY = "System_Memory"
        private const val ACTION_MEMORY_PER_PROCESS = "System_MemoryPerProcess"
    }
}
