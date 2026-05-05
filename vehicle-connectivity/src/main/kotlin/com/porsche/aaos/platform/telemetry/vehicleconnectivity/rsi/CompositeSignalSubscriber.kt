package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.asi.SportChronoAsiServiceConnector
import kotlinx.coroutines.flow.Flow

/**
 * Composite signal subscriber that routes signal subscriptions to the appropriate
 * backend: ASI-backed signals go to [SportChronoAsiServiceConnector],
 * RSI RUDI proxy signals go to [RsiChargingDataSource], and all others
 * go to [ExlapRsiSignalSubscriber].
 */
class CompositeSignalSubscriber(
    private val asiConnector: SportChronoAsiServiceConnector,
    private val rsiChargingDataSource: RsiChargingDataSource,
    private val rsiSubscriber: ExlapRsiSignalSubscriber,
) : RsiSignalSubscriber {
    override fun <T : Any> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>> = when {
        asiConnector.hasSignal(signalUrl) -> asiConnector.observeSignal(signalUrl)
        rsiChargingDataSource.hasSignal(signalUrl) -> rsiChargingDataSource.observeSignal(signalUrl)
        else -> rsiSubscriber.subscribeToSignal(signalUrl)
    }
}
