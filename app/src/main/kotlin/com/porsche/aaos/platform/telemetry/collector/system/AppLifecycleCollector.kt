package com.porsche.aaos.platform.telemetry.collector.system

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
    private val foregroundByDisplay = mutableMapOf<Int, ComponentName>()
    private var initialPollDone = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting app lifecycle monitoring")

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

                // Detect changes: emit single FocusChanged event with previous/current.
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

                    val previousMap: Map<String, Any>? = previous?.let {
                        mapOf(
                            "package" to it.packageName,
                            "class" to it.className,
                            "displayId" to displayId,
                            "displayType" to displayType,
                        )
                    }
                    val currentMap: Map<String, Any>? = current?.let {
                        mapOf(
                            "package" to it.packageName,
                            "class" to it.className,
                            "displayId" to displayId,
                            "displayType" to displayType,
                        )
                    }

                    logger.d(
                        TAG,
                        "Focus changed: display=$displayId ($displayType)" +
                            " ${previous?.packageName ?: "null"} → ${current?.packageName ?: "null"}",
                    )
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "App_FocusChanged",
                                "trigger" to "user",
                                "metadata" to mapOf(
                                    "previous" to previousMap,
                                    "current" to currentMap,
                                ),
                            ),
                        ),
                    )
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

    companion object {
        private const val TAG = "AppLifecycleCollector"
        private const val POLL_INTERVAL_MS = 500L

        // Physical display IDs on Scylla (0=Center, 1=Instrument, 2=Passenger, 3=Rear).
        // Virtual displays (10, 24, etc.) are off-screen render targets used by OEM services.
        private val PHYSICAL_DISPLAY_IDS = setOf(0, 1, 2, 3)
    }
}
