package com.porsche.datacollector.telemetry

data class TelemetryEvent(
    val signalId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, Any?>,
) {
    init {
        require(payload.containsKey("actionName")) { "payload must contain 'actionName'" }
    }

    companion object {
        private const val SIGNAL_BASE = "com.porsche.aaos.datacollector.core"

        fun signalId(collectorName: String): String = "$SIGNAL_BASE.$collectorName"
    }
}
