package com.porsche.datacollector.telemetry

import com.porsche.sportapps.core.logging.Logger
import javax.inject.Inject

class LogTelemetry @Inject constructor(
    private val logger: Logger,
) : Telemetry {

    override fun send(event: TelemetryEvent) {
        logger.d(
            TAG,
            "[${event.eventId}] ts=${event.timestamp} payload=${event.payload}",
        )
    }

    companion object {
        private const val TAG = "LogTelemetry"
    }
}
