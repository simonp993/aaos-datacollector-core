package com.porsche.datacollector.vehicleconnectivity.rsi

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
