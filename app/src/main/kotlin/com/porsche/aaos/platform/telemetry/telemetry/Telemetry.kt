package com.porsche.aaos.platform.telemetry.telemetry

interface Telemetry {
    fun send(event: TelemetryEvent)
}
