package com.porsche.aaos.platform.telemetry.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class TimeChangeCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "TimeChange"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var receiver: BroadcastReceiver? = null

    override suspend fun start() {
        logger.i(TAG, "Starting time change monitoring")

        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val actionName = when (intent.action) {
                    Intent.ACTION_TIME_CHANGED -> "TimeChange_ManualTimeSet"
                    Intent.ACTION_TIMEZONE_CHANGED -> "TimeChange_TimezoneChanged"
                    Intent.ACTION_DATE_CHANGED -> "TimeChange_DateChanged"
                    else -> return
                }

                val trigger = when (intent.action) {
                    Intent.ACTION_TIME_CHANGED -> "user"
                    else -> "system"
                }

                val now = Instant.now()
                val zone = ZoneId.systemDefault()

                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to actionName,
                            "trigger" to trigger,
                            "metadata" to mapOf(
                                "epochMillis" to now.toEpochMilli(),
                                "iso8601" to DateTimeFormatter.ISO_OFFSET_DATE_TIME
                                    .format(now.atZone(zone)),
                                "timezone" to zone.id,
                            ),
                        ),
                    ),
                )
                logger.d(TAG, "$actionName at ${now.atZone(zone)}")
            }
        }
        receiver = broadcastReceiver

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        context.registerReceiver(broadcastReceiver, filter)
    }

    override fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "TimeChangeCollector"
    }
}
