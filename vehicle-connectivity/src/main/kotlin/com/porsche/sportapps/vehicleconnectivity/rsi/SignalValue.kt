package com.porsche.sportapps.vehicleconnectivity.rsi

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
