package com.porsche.datacollector.collector.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.UserHandle
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

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
    private var lastQueryTime = System.currentTimeMillis()

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting app lifecycle monitoring")

        // User-facing apps run on user 10 in AAOS; our service runs as user 0.
        // createContextAsUser + UserHandle.of are @SystemApi → use reflection.
        val user10Context = try {
            val userHandle = UserHandle::class.java
                .getDeclaredMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, FOREGROUND_USER_ID) as UserHandle
            Context::class.java
                .getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                .invoke(context, userHandle, 0) as Context
        } catch (e: Exception) {
            logger.w(TAG, "Cannot create user $FOREGROUND_USER_ID context, falling back to user 0: ${e.message}")
            context
        }
        val usageStatsManager = user10Context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val contextUserId = try {
            Context::class.java.getMethod("getUserId").invoke(user10Context)
        } catch (_: Exception) { "unknown" }
        logger.i(TAG, "UsageStatsManager context userId=$contextUserId")

        while (running && coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(lastQueryTime, now)
            val event = UsageEvents.Event()
            var eventCount = 0

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                eventCount++
                logger.d(TAG, "Event: type=${event.eventType} pkg=${event.packageName} cls=${event.className}")
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "AppLifecycle_Resumed",
                                    "metadata" to mapOf(
                                        "package" to event.packageName,
                                        "class" to event.className,
                                    ),
                                ),
                            ),
                        )
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        telemetry.send(
                            TelemetryEvent(
                                signalId = signalId,
                                payload = mapOf(
                                    "actionName" to "AppLifecycle_Paused",
                                    "metadata" to mapOf(
                                        "package" to event.packageName,
                                        "class" to event.className,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }

            lastQueryTime = now
            if (eventCount > 0) logger.d(TAG, "Polled $eventCount events in window")
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "AppLifecycleCollector"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val FOREGROUND_USER_ID = 10
    }
}
