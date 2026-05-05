package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import kotlinx.coroutines.flow.Flow

interface RsiSignalSubscriber {
    fun <T : Any> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>>
}
