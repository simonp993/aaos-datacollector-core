package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.UserHandle
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

    // Watermark per user: only report exits newer than this timestamp
    private val lastSeenTimestampByUser = mutableMapOf<Int, Long>()
    private var userIds: List<Int> = listOf(0)

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting app-exit monitoring (${POLL_INTERVAL_MS / 1_000}s poll)")

        delay(STAGGER_DELAY_MS)

        // Seed watermarks for currently known users
        val initialUsers = discoverUserIds()
        logger.i(TAG, "Initial users for app-exit monitoring: $initialUsers")
        initialUsers.forEach { userId ->
            val userAm = activityManagerForUser(userId)
            seedWatermark(userAm, userId)
        }

        while (running && coroutineContext.isActive) {
            delay(POLL_INTERVAL_MS)
            // Re-discover users each cycle to catch added/removed users
            userIds = discoverUserIds()
            collectExitsAllUsers()
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun seedWatermark(am: ActivityManager, userId: Int) {
        try {
            val recent = am.getHistoricalProcessExitReasons(null, 0, 1)
            if (recent.isNotEmpty()) {
                lastSeenTimestampByUser[userId] = recent[0].timestamp
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to seed watermark for user $userId: ${e.message}")
        }
    }

    private fun collectExitsAllUsers() {
        userIds.forEach { userId ->
            // Seed watermark for newly discovered users to avoid replaying their full history
            if (userId !in lastSeenTimestampByUser) {
                seedWatermark(activityManagerForUser(userId), userId)
                return@forEach
            }
            val userAm = activityManagerForUser(userId)
            collectExitsForUser(userAm, userId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun collectExitsForUser(am: ActivityManager, userId: Int) {
        try {
            val watermark = lastSeenTimestampByUser[userId] ?: 0L
            val exits = am.getHistoricalProcessExitReasons(null, 0, MAX_RESULTS)
            val newExits = exits.filter { it.timestamp > watermark }
            if (newExits.isEmpty()) return

            lastSeenTimestampByUser[userId] = newExits.maxOf { it.timestamp }

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

            logger.d(TAG, "Reported ${exitRecords.size} new app exits for user $userId")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to collect app exits for user $userId: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun discoverUserIds(): List<Int> {
        val um = context.getSystemService(Context.USER_SERVICE) ?: return listOf(0)

        try {
            val usersMethod = um.javaClass.getMethod("getUsers", Boolean::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            val userInfoList = usersMethod.invoke(um, true) as? List<*>
            if (!userInfoList.isNullOrEmpty()) {
                val ids = userInfoList.mapNotNull { userInfo ->
                    userInfo?.javaClass?.getField("id")?.getInt(userInfo)
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getUsers(boolean) failed: ${e.message}")
        }

        try {
            val usersMethod = um.javaClass.getMethod("getUsers")
            @Suppress("UNCHECKED_CAST")
            val userInfoList = usersMethod.invoke(um) as? List<*>
            if (!userInfoList.isNullOrEmpty()) {
                val ids = userInfoList.mapNotNull { userInfo ->
                    userInfo?.javaClass?.getField("id")?.getInt(userInfo)
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getUsers() failed: ${e.message}")
        }

        try {
            val aliveMethod = um.javaClass.getMethod("getAliveUsers")
            @Suppress("UNCHECKED_CAST")
            val handles = aliveMethod.invoke(um) as? List<*>
            if (!handles.isNullOrEmpty()) {
                val getIdMethod = UserHandle::class.java.getMethod("getIdentifier")
                val ids = handles.mapNotNull { handle ->
                    (handle as? UserHandle)?.let { getIdMethod.invoke(it) as? Int }
                }
                if (ids.isNotEmpty()) return ids
            }
        } catch (e: Exception) {
            logger.d(TAG, "getAliveUsers() failed: ${e.message}")
        }

        logger.w(TAG, "All user discovery methods failed, defaulting to user 0")
        return listOf(0)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun activityManagerForUser(userId: Int): ActivityManager {
        if (userId == 0) {
            return context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        }
        return try {
            val userHandle = UserHandle::class.java
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId) as UserHandle
            val userContext = context.javaClass
                .getMethod(
                    "createContextAsUser",
                    UserHandle::class.java,
                    Int::class.javaPrimitiveType,
                )
                .invoke(context, userHandle, 0) as Context
            userContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        } catch (e: Exception) {
            logger.w(TAG, "Failed to create AM for user $userId, using default: ${e.message}")
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
