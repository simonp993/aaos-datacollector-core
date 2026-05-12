package com.porsche.aaos.platform.telemetry.collector.system

import android.car.Car
import android.car.watchdog.CarWatchdogManager
import android.car.watchdog.IoOveruseStats
import android.content.Context
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Collects per-package I/O statistics from CarWatchdog's periodic reports.
 *
 * Primary source: `dumpsys android.automotive.watchdog.ICarWatchdog/default`
 * (via [CarWatchdogDataSource]) which provides per-UID I/O reads and writes
 * in foreground/background with byte counts and fsync counts.
 *
 * Also reads own-package overuse stats from [CarWatchdogManager] API.
 *
 * Emits **System_IoPerPackage** every 60 s with per-UID I/O delta.
 * Delta is computed between consecutive CarWatchdog snapshots.
 */
class IoCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
    private val watchdogDataSource: CarWatchdogDataSource,
) : Collector {

    override val name: String = "Io"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Previous snapshot for delta computation (keyed by "userId:packageName")
    private var prevReads = emptyMap<String, UidIoWatchdogEntry>()
    private var prevWrites = emptyMap<String, UidIoWatchdogEntry>()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting I/O monitoring (${POLL_INTERVAL_MS / MILLIS_PER_SEC}s interval)")

        delay(STAGGER_DELAY_MS)

        // Seed the first snapshot (no delta emitted)
        seedPreviousSnapshot()

        while (running) {
            delay(POLL_INTERVAL_MS)
            collectIo()
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    private fun seedPreviousSnapshot() {
        val snapshot = watchdogDataSource.getLatestSnapshot() ?: return
        prevReads = snapshot.ioReads.associateBy { ioKey(it) }
        prevWrites = snapshot.ioWrites.associateBy { ioKey(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectIo() {
        try {
            val now = System.currentTimeMillis()
            val snapshot = watchdogDataSource.getLatestSnapshot() ?: return

            val currentReads = snapshot.ioReads.associateBy { ioKey(it) }
            val currentWrites = snapshot.ioWrites.associateBy { ioKey(it) }

            val packages = mutableListOf<List<Any>>()

            // Merge all UIDs from reads and writes
            val allKeys = currentReads.keys + currentWrites.keys
            for (key in allKeys.distinct()) {
                val read = currentReads[key]
                val prevRead = prevReads[key]
                val write = currentWrites[key]
                val prevWrite = prevWrites[key]

                val deltaFgRead = deltaBytes(read?.fgBytes, prevRead?.fgBytes)
                val deltaBgRead = deltaBytes(read?.bgBytes, prevRead?.bgBytes)
                val deltaFgWrite = deltaBytes(write?.fgBytes, prevWrite?.fgBytes)
                val deltaBgWrite = deltaBytes(write?.bgBytes, prevWrite?.bgBytes)
                val deltaFgFsyncR = deltaBytes(read?.fgFsync, prevRead?.fgFsync)
                val deltaBgFsyncR = deltaBytes(read?.bgFsync, prevRead?.bgFsync)
                val deltaFgFsyncW = deltaBytes(write?.fgFsync, prevWrite?.fgFsync)
                val deltaBgFsyncW = deltaBytes(write?.bgFsync, prevWrite?.bgFsync)

                val totalDelta = deltaFgRead + deltaBgRead + deltaFgWrite + deltaBgWrite
                if (totalDelta == 0L) continue

                val entry = read ?: write ?: continue
                packages.add(
                    listOf(
                        entry.userId,
                        entry.packageName,
                        deltaFgRead,
                        deltaFgWrite,
                        deltaBgRead,
                        deltaBgWrite,
                        deltaFgFsyncR + deltaFgFsyncW,
                        deltaBgFsyncR + deltaBgFsyncW,
                    ),
                )
            }

            prevReads = currentReads
            prevWrites = currentWrites

            if (packages.isEmpty()) return

            // Also try own-package overuse stats from CarWatchdogManager API
            val ownOveruse = readOwnOveruseStats()

            val metadata = mutableMapOf<String, Any>(
                "ioSchema" to listOf(
                    "userId", "packageName",
                    "fgReadBytes", "fgWriteBytes",
                    "bgReadBytes", "bgWriteBytes",
                    "fgFsync", "bgFsync",
                ),
                "packages" to packages,
            )
            if (ownOveruse.isNotEmpty()) {
                metadata["ownOveruse"] = ownOveruse
            }

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to ACTION_IO_PER_PACKAGE,
                        "trigger" to "heartbeat",
                        "metadata" to metadata,
                    ),
                    timestamp = now,
                ),
            )
        } catch (e: Exception) {
            logger.e(TAG, "I/O collection failed: ${e.message}")
        }
    }

    // ── CarWatchdogManager overuse stats ──────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private fun readOwnOveruseStats(): Map<String, Any> {
        return try {
            val car = Car.createCar(context) ?: return emptyMap()
            val wdm = car.getCarManager(Car.CAR_WATCHDOG_SERVICE) as? CarWatchdogManager
                ?: return emptyMap()

            val stats = wdm.getResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY,
            )

            val ioStats = stats.ioOveruseStats ?: return emptyMap()
            mapOf(
                "totalBytesWritten" to ioStats.totalBytesWritten,
                "remainingWriteBytes" to extractRemainingBytes(ioStats),
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun extractRemainingBytes(ioStats: IoOveruseStats): Long {
        return try {
            ioStats.remainingWriteBytes?.let { remaining ->
                remaining.foregroundModeBytes + remaining.backgroundModeBytes +
                    remaining.garageModeBytes
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun ioKey(entry: UidIoWatchdogEntry): String =
        "${entry.userId}:${entry.packageName}"

    private fun deltaBytes(current: Long?, previous: Long?): Long {
        val c = current ?: 0L
        val p = previous ?: 0L
        return (c - p).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "IoCollector"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 20_000L
        private const val MILLIS_PER_SEC = 1_000L

        private const val ACTION_IO_PER_PACKAGE = "System_IoPerPackage"
    }
}
