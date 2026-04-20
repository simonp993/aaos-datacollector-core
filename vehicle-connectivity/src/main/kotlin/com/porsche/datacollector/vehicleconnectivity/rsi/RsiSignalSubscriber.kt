package com.porsche.datacollector.vehicleconnectivity.rsi

import kotlinx.coroutines.flow.Flow

interface RsiSignalSubscriber {
    fun <T : Any> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>>
}
