package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

data class SignalValue<T>(
    val value: T,
    val quality: SignalQuality,
    val timestamp: Long,
)

enum class SignalQuality {
    VALID,
    INVALID,
    NOT_AVAILABLE,
}
