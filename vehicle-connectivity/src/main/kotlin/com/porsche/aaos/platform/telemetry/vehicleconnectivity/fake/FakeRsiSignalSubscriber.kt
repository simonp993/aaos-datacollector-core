package com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.RsiSignalSubscriber
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.SignalValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeRsiSignalSubscriber : RsiSignalSubscriber {
    private val signalFlows = mutableMapOf<String, MutableSharedFlow<SignalValue<Any>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribeToSignal(
        signalUrl: String,
    ): Flow<SignalValue<T>> = getOrCreateFlow(signalUrl) as Flow<SignalValue<T>>

    suspend fun <T : Any> emit(
        signalUrl: String,
        signalValue: SignalValue<T>,
    ) {
        @Suppress("UNCHECKED_CAST")
        getOrCreateFlow(signalUrl).emit(signalValue as SignalValue<Any>)
    }

    private fun getOrCreateFlow(
        signalUrl: String,
    ): MutableSharedFlow<SignalValue<Any>> = signalFlows.getOrPut(signalUrl) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
    }
}
