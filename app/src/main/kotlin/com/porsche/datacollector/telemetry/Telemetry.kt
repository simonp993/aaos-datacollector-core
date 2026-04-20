package com.porsche.datacollector.telemetry

interface Telemetry {
    fun send(event: TelemetryEvent)
}
