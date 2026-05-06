package com.porsche.aaos.platform.telemetry.collector.system

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.content.ComponentName
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

@Suppress("DiscouragedPrivateApi")
class AppLifecycleCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "AppLifecycle"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false

    // Tracks the foreground activity per display to detect transitions.
    // Key = displayId, Value = topActivity of the topmost task on that display.
    // getAllRootTaskInfos() returns tasks in z-order (top first), so the first
    // task per displayId is the foreground task the user sees.
    private val foregroundByDisplay = mutableMapOf<Int, ComponentName>()
    private var initialPollDone = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting app lifecycle monitoring")

        // Register ApplicationStartInfo listener for app startup times.
        // Requires DUMP permission (platform-signed) to observe other apps' starts.
        // TODO: Test on platform-signed build — will only report own app starts without DUMP.
        registerStartInfoListener()

        // IActivityTaskManager.getAllRootTaskInfos() returns tasks for ALL users.
        // This is a @SystemApi — use reflection since our app is platform-signed.
        val atmService = try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            atmClass.getMethod("getService").invoke(null)
        } catch (e: Exception) {
            logger.e(TAG, "Cannot obtain ActivityTaskManager service: ${e.message}")
            return
        }
        val getAllRootTaskInfos = try {
            atmService.javaClass.getMethod("getAllRootTaskInfos")
        } catch (e: Exception) {
            logger.e(TAG, "getAllRootTaskInfos not available: ${e.message}")
            return
        }
        logger.i(TAG, "Using IActivityTaskManager.getAllRootTaskInfos() for cross-user monitoring")

        // Cache the field accessors from TaskInfo base class.
        val taskInfoClass = Class.forName("android.app.TaskInfo")
        val topActivityField = taskInfoClass.getField("topActivity")
        val displayIdField = taskInfoClass.getField("displayId")

        while (running && coroutineContext.isActive) {
            try {
                @Suppress("UNCHECKED_CAST")
                val tasks = getAllRootTaskInfos.invoke(atmService) as List<Any>

                // Build current foreground: first task per displayId (z-order = top first).
                val currentForeground = mutableMapOf<Int, ComponentName>()
                for (task in tasks) {
                    val topActivity = topActivityField.get(task) as? ComponentName ?: continue
                    val displayId = displayIdField.getInt(task)
                    currentForeground.putIfAbsent(displayId, topActivity)
                }

                if (!initialPollDone && currentForeground.isNotEmpty()) {
                    val summary = currentForeground.entries.joinToString {
                        "display=${it.key} → ${it.value.packageName}"
                    }
                    logger.i(TAG, "Initial poll: $summary")
                    initialPollDone = true
                }

                // Detect changes: emit Paused for old, Resumed for new.
                // Physical displays (0-3) are user-facing screens.
                // Virtual displays (10, 24, etc.) are off-screen render targets
                // (e.g. navigation map → IC compositor). Tracked separately as they
                // indicate what content is ready for display, even if not currently routed.
                val allDisplays = foregroundByDisplay.keys + currentForeground.keys
                for (displayId in allDisplays) {
                    val previous = foregroundByDisplay[displayId]
                    val current = currentForeground[displayId]

                    if (previous == current) continue

                    val displayType = if (displayId in PHYSICAL_DISPLAY_IDS) "physical" else "virtual"

                    if (previous != null) {
                        logger.d(
                            TAG,
                            "Activity paused: display=$displayId ($displayType)" +
                                " pkg=${previous.packageName} cls=${previous.className}",
                        )
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "AppLifecycle_Paused",
                                    "trigger" to "user",
                                    "metadata" to mapOf(
                                        "package" to previous.packageName,
                                        "class" to previous.className,
                                        "displayId" to displayId,
                                        "displayType" to displayType,
                                    ),
                                ),
                            ),
                        )
                    }
                    if (current != null) {
                        logger.d(
                            TAG,
                            "Activity resumed: display=$displayId ($displayType)" +
                                " pkg=${current.packageName} cls=${current.className}",
                        )
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "AppLifecycle_Resumed",
                                    "trigger" to "user",
                                    "metadata" to mapOf(
                                        "package" to current.packageName,
                                        "class" to current.className,
                                        "displayId" to displayId,
                                        "displayType" to displayType,
                                    ),
                                ),
                            ),
                        )
                    }
                }

                // Update state: replace with current snapshot.
                foregroundByDisplay.clear()
                foregroundByDisplay.putAll(currentForeground)
            } catch (e: Exception) {
                val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
                logger.e(TAG, "Error polling tasks: ${cause?.javaClass?.simpleName}: ${cause?.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    /**
     * Registers an ApplicationStartInfo completion listener (API 35+).
     * Reports app startup times with system-determined cold/warm/hot classification.
     *
     * Requires DUMP permission to observe other apps' starts (platform-signed).
     * Without it, only reports our own process starts.
     *
     * TODO: Test on platform-signed build on real device.
     *       Verify getHistoricalProcessStartReasons returns other apps' starts with DUMP.
     *       Consider polling getHistoricalProcessStartReasons() periodically as fallback
     *       if the listener doesn't fire for other apps.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun registerStartInfoListener() {
        try {
            val am = context.getSystemService(ActivityManager::class.java)

            // Emit any historical starts since last boot (batch catchup).
            val history = am.getHistoricalProcessStartReasons(MAX_START_INFO_HISTORY)
            if (history.isNotEmpty()) {
                logger.i(TAG, "ApplicationStartInfo: ${history.size} historical entries available")
            }

            // Register real-time listener for future app starts.
            am.addApplicationStartInfoCompletionListener(
                context.mainExecutor,
            ) { startInfo -> emitStartInfo(startInfo) }
            logger.i(TAG, "ApplicationStartInfo listener registered")
        } catch (e: SecurityException) {
            logger.w(TAG, "ApplicationStartInfo requires DUMP permission (platform-signed): ${e.message}")
        } catch (e: Exception) {
            logger.w(TAG, "ApplicationStartInfo not available: ${e.message}")
        }
    }

    private fun emitStartInfo(startInfo: ApplicationStartInfo) {
        val startType = when (startInfo.startType) {
            ApplicationStartInfo.START_TYPE_COLD -> "cold"
            ApplicationStartInfo.START_TYPE_WARM -> "warm"
            ApplicationStartInfo.START_TYPE_HOT -> "hot"
            else -> "unknown"
        }
        val reason = when (startInfo.reason) {
            ApplicationStartInfo.START_REASON_LAUNCHER -> "launcher"
            ApplicationStartInfo.START_REASON_SERVICE -> "service"
            ApplicationStartInfo.START_REASON_BROADCAST -> "broadcast"
            ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "content_provider"
            ApplicationStartInfo.START_REASON_BACKUP -> "backup"
            ApplicationStartInfo.START_REASON_ALARM -> "alarm"
            ApplicationStartInfo.START_REASON_PUSH -> "push"
            else -> "other"
        }

        // Extract key timestamps (nanoseconds since boot → convert to millis).
        val timestamps = startInfo.startupTimestamps
        val launchMs = timestamps.entries
            .filter { it.value > 0 }
            .takeIf { it.isNotEmpty() }
            ?.let { entries ->
                val first = entries.minOf { it.value }
                val last = entries.maxOf { it.value }
                (last - first) / 1_000_000 // ns → ms
            }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "AppStart_TimeUntilStarted",
                    "trigger" to "system",
                    "metadata" to buildMap {
                        put("package", startInfo.processName ?: "unknown")
                        put("startType", startType)
                        put("reason", reason)
                        if (launchMs != null) put("durationMs", launchMs)
                        put("pid", startInfo.pid)
                    },
                ),
            ),
        )
        logger.d(TAG, "AppStart: ${startInfo.processName} ($startType/$reason) ${launchMs ?: "?"}ms")
    }

    companion object {
        private const val TAG = "AppLifecycleCollector"
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_START_INFO_HISTORY = 30

        // Physical display IDs on Scylla (0=Center, 1=Instrument, 2=Passenger, 3=Rear).
        // Virtual displays (10, 24, etc.) are off-screen render targets used by OEM services.
        private val PHYSICAL_DISPLAY_IDS = setOf(0, 1, 2, 3)
    }
}
