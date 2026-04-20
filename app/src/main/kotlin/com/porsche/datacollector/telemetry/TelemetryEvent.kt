package com.porsche.datacollector.telemetry

data class TelemetryEvent(
    val eventId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any?>,
)
