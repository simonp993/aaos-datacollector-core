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
    // Key = displayId, Value = topActivity of the topmost task on that display.
    // getAllRootTaskInfos() returns tasks in z-order (top first), so the first
    // task per displayId is the foreground task the user sees.
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

                // Detect changes: emit Paused for old, Resumed for new.
                val allDisplays = foregroundByDisplay.keys + currentForeground.keys
                for (displayId in allDisplays) {
                    val previous = foregroundByDisplay[displayId]
                    val current = currentForeground[displayId]

                    if (previous == current) continue

                    if (previous != null) {
                        logger.d(
                            TAG,
                            "Activity paused: display=$displayId" +
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
                                    ),
                                ),
                            ),
                        )
                    }
                    if (current != null) {
                        logger.d(
                            TAG,
                            "Activity resumed: display=$displayId" +
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

    companion object {
        private const val TAG = "AppLifecycleCollector"
        private const val POLL_INTERVAL_MS = 500L
    }
}
