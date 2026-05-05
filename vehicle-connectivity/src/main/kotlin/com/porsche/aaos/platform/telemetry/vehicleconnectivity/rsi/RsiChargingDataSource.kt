package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import android.content.Context
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import de.esolutions.fw.android.rsi.client.rx.IRsiAdmin
import de.esolutions.fw.android.rsi.client.rx.RsiAdmin
import de.esolutions.fw.rudi.services.viwi.ImmutableViwiParams
import de.esolutions.fw.rudi.viwi.service.chargingmanagementcore.v101.IChargingManagementCoreProxy
import de.esolutions.fw.rudi.viwi.service.chargingmanagementcore.v101.ValueIndicationsObjectTypeEnum
import de.esolutions.fw.rudi.viwi.service.chargingmanagementcore.v101.registry.ChargingManagementCoreProxyFactory
import io.reactivex.rxjava3.disposables.Disposable
import java.util.Optional

/**
 * Provides battery SOC and target SOC for MMTR via RSI RUDI typed proxies.
 *
 * Battery SOC uses the ViWi RSI [ChargingManagementCoreProxyFactory] typed proxy,
 * which knows the correct service URI and data model.
 * Target SOC defaults to 65% (matching legacy behavior).
 * Charging planner is sourced from ASI restriction reason (see [SportChronoAsiServiceConnector]).
 */
class RsiChargingDataSource(
    private val context: Context,
    private val logger: Logger,
) {
    private val rsiAdmin: IRsiAdmin by lazy { RsiAdmin.start(context) }

    private var chargingProxy: IChargingManagementCoreProxy? = null
    private var socSubscription: Disposable? = null

    private val _batterySoc = MutableSharedFlow<SignalValue<Any>>(replay = 1, extraBufferCapacity = 1)
    private val _targetSoc = MutableSharedFlow<SignalValue<Any>>(replay = 1, extraBufferCapacity = 1)

    @Suppress("TooGenericExceptionCaught")
    fun start() {
        val ts = System.currentTimeMillis()
        // Target SOC: legacy hardcodes 65%. Emit default until a real data source is wired.
        _targetSoc.tryEmit(SignalValue(DEFAULT_TARGET_SOC, SignalQuality.VALID, ts))

        try {
            startChargingManagement()
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start ChargingManagement proxy", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun stop() {
        socSubscription?.dispose()
        socSubscription = null
        try {
            chargingProxy?.stop()
        } catch (e: Exception) {
            logger.e(TAG, "Error stopping charging proxy", e)
        }
        chargingProxy = null
    }

    fun hasSignal(signalUrl: String): Boolean = signalUrl in RSI_SIGNAL_URLS

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeSignal(signalUrl: String): Flow<SignalValue<T>> = when (signalUrl) {
        SIGNAL_BATTERY_SOC -> _batterySoc as Flow<SignalValue<T>>
        SIGNAL_TARGET_SOC -> _targetSoc as Flow<SignalValue<T>>
        else -> throw IllegalArgumentException("Unknown RSI charging signal: $signalUrl")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startChargingManagement() {
        val proxy = ChargingManagementCoreProxyFactory.create(rsiAdmin).instance
        chargingProxy = proxy
        proxy.start()
        logger.d(TAG, "ChargingManagement proxy started")

        socSubscription = proxy
            .valueIndications()
            .retrieveValueIndicationsObjects(
                Optional.of(ImmutableViwiParams.builder().autosubscribe(true).build()),
            ).subscribe(
                { page ->
                    val ts = System.currentTimeMillis()
                    page.data.forEach { data ->
                        try {
                            if (data.type.isPresent &&
                                data.type.get() == ValueIndicationsObjectTypeEnum.CHARGINGPROCESSINFORMATIONCURRENTSOC
                            ) {
                                val soc = data.currentValue.get().toDouble()
                                logger.d(TAG, "Received battery SOC update")
                                _batterySoc.tryEmit(SignalValue(soc, SignalQuality.VALID, ts))
                            }
                        } catch (e: Exception) {
                            logger.e(TAG, "Error parsing SOC data", e)
                        }
                    }
                },
                { error ->
                    logger.e(TAG, "ChargingManagement error: ${error.message}")
                    _batterySoc.tryEmit(SignalValue(0.0, SignalQuality.NOT_AVAILABLE, System.currentTimeMillis()))
                },
            )
    }

    companion object {
        private const val TAG = "RsiChargingDataSource"
        private const val DEFAULT_TARGET_SOC = 65.0

        internal const val SIGNAL_BATTERY_SOC = "CCL://currentBatteryCharge/thev"
        internal const val SIGNAL_TARGET_SOC = "CCL://targetSOC"

        private val RSI_SIGNAL_URLS =
            setOf(
                SIGNAL_BATTERY_SOC,
                SIGNAL_TARGET_SOC,
            )
    }
}
