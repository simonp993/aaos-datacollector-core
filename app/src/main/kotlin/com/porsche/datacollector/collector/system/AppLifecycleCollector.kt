package com.porsche.datacollector.collector.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

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
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        while (running && coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(lastQueryTime, now)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
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
    }
}
