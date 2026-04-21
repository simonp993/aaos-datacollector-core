package com.porsche.datacollector.collector.system

import android.content.ComponentName
import android.content.Context
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
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

    // Tracks the last-known top activity per taskId to detect transitions.
    private val lastTopActivity = mutableMapOf<Int, ComponentName>()

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

        // Cache the field accessors for topActivity and taskId from TaskInfo base class.
        val taskInfoClass = Class.forName("android.app.TaskInfo")
        val topActivityField = taskInfoClass.getField("topActivity")
        val taskIdField = taskInfoClass.getField("taskId")
        val displayIdField = taskInfoClass.getField("displayId")

        while (running && coroutineContext.isActive) {
            try {
                @Suppress("UNCHECKED_CAST")
                val tasks = getAllRootTaskInfos.invoke(atmService) as List<Any>
                if (lastTopActivity.isEmpty() && tasks.isNotEmpty()) {
                    val tops = tasks.mapNotNull {
                        (topActivityField.get(it) as? ComponentName)?.packageName
                    }
                    logger.i(TAG, "Initial poll: ${tasks.size} tasks, tops=$tops")
                }
                for (task in tasks) {
                    val topActivity = topActivityField.get(task) as? ComponentName ?: continue
                    val taskId = taskIdField.getInt(task)
                    val displayId = displayIdField.getInt(task)
                    val previous = lastTopActivity[taskId]
                    if (previous != topActivity) {
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
                                        "metadata" to mapOf(
                                            "package" to previous.packageName,
                                            "class" to previous.className,
                                            "displayId" to displayId,
                                        ),
                                    ),
                                ),
                            )
                        }
                        logger.d(
                            TAG,
                            "Activity resumed: display=$displayId" +
                                " pkg=${topActivity.packageName} cls=${topActivity.className}",
                        )
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "AppLifecycle_Resumed",
                                    "metadata" to mapOf(
                                        "package" to topActivity.packageName,
                                        "class" to topActivity.className,
                                        "displayId" to displayId,
                                    ),
                                ),
                            ),
                        )
                        lastTopActivity[taskId] = topActivity
                    }
                }
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
