package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
 * Detects application exits (crashes, ANRs, low-memory kills, etc.) across
 * all packages by polling [ActivityManager.getHistoricalProcessExitReasons].
 *
 * Emits **App_ExitDetected** events with delta-based deduplication: only
 * exits whose timestamp is newer than the last seen are reported.
 */
class AppExitCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "AppExit"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Watermark: only report exits newer than this timestamp
    private var lastSeenTimestamp = 0L

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting app-exit monitoring (${POLL_INTERVAL_MS / 1_000}s poll)")

        delay(STAGGER_DELAY_MS)

        // Seed watermark to avoid replaying entire history on first run
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        seedWatermark(am)

        while (running && coroutineContext.isActive) {
            delay(POLL_INTERVAL_MS)
            collectExits(am)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun seedWatermark(am: ActivityManager) {
        try {
            val recent = am.getHistoricalProcessExitReasons(null, 0, 1)
            if (recent.isNotEmpty()) {
                lastSeenTimestamp = recent[0].timestamp
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to seed watermark: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectExits(am: ActivityManager) {
        try {
            val exits = am.getHistoricalProcessExitReasons(null, 0, MAX_RESULTS)
            val newExits = exits.filter { it.timestamp > lastSeenTimestamp }
            if (newExits.isEmpty()) return

            lastSeenTimestamp = newExits.maxOf { it.timestamp }

            val exitRecords = newExits.map { info ->
                mapOf(
                    "processName" to (info.processName ?: "unknown"),
                    "pid" to info.pid,
                    "uid" to info.realUid,
                    "user" to userFromUid(info.realUid),
                    "reason" to reasonLabel(info.reason),
                    "reasonCode" to info.reason,
                    "description" to (info.description ?: ""),
                    "exitTimestamp" to info.timestamp,
                    "rssKb" to (info.rss / KB_DIVISOR),
                    "importance" to info.importance,
                    "status" to info.status,
                )
            }

            telemetry.send(
                TelemetryEvent(
                    signalId = signalId,
                    payload = mapOf(
                        "actionName" to ACTION_APP_EXIT,
                        "trigger" to "heartbeat",
                        "metadata" to mapOf(
                            "count" to exitRecords.size,
                            "exits" to exitRecords,
                        ),
                    ),
                ),
            )

            logger.d(TAG, "Reported ${exitRecords.size} new app exits")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to collect app exits: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AppExitCollector"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 15_000L
        private const val MAX_RESULTS = 50
        private const val KB_DIVISOR = 1_024L

        private const val ACTION_APP_EXIT = "App_ExitDetected"

        // UID ranges: user 0 = 10000-19999, user 10 = 1010000-1019999
        private const val APP_UID_BASE = 10_000
        private const val USER_UID_MULTIPLIER = 100_000

        @Suppress("MagicNumber")
        private fun userFromUid(uid: Int): String {
            val userId = uid / USER_UID_MULTIPLIER
            return "u$userId"
        }

        @Suppress("CyclomaticComplexity")
        private fun reasonLabel(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> "UNKNOWN($reason)"
        }
    }
}
