package com.porsche.aaos.platform.telemetry.telemetry

import com.porsche.aaos.platform.telemetry.core.logging.Logger
import javax.inject.Inject

class LogTelemetry @Inject constructor(
    private val logger: Logger,
) : Telemetry {

    override fun send(event: TelemetryEvent) {
        logger.d(
            TAG,
            "[${event.signalId}] ${event.payload["actionName"]} ts=${event.timestamp}" +
                " payload=${event.payload}",
        )
    }

    companion object {
        private const val TAG = "LogTelemetry"
    }
}
