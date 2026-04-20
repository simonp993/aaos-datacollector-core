package com.porsche.sportapps.vehicleconnectivity.rsi

import kotlinx.coroutines.flow.Flow

interface RsiSignalSubscriber {
    fun <T : Any> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>>
}
