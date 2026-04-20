package com.porsche.datacollector.vehicleconnectivity.asi

import android.content.Context
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.vehicleconnectivity.rsi.SignalQuality
import com.porsche.datacollector.vehicleconnectivity.rsi.SignalValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.esolutions.android.framework.clients.AsiAdmin
import de.esolutions.android.framework.clients.IServiceCallback
import de.esolutions.fw.android.comm.asi.sportchronoservice.ASISportChronoServiceConsts
import de.esolutions.fw.android.comm.asi.sportchronoservice.DamperSettings
import de.esolutions.fw.android.comm.asi.sportchronoservice.ERaceConditioning_State
import de.esolutions.fw.android.comm.asi.sportchronoservice.ERaceMode_State
import de.esolutions.fw.android.comm.asi.sportchronoservice.EStopwatch_State
import de.esolutions.fw.android.comm.asi.sportchronoservice.IASISportChronoServiceServiceListener
import de.esolutions.fw.android.comm.asi.sportchronoservice.RaceConditioningExtended
import de.esolutions.fw.android.comm.asi.sportchronoservice.SuspensionHeightInfo
import de.esolutions.fw.android.comm.asi.sportchronoservice.SuspensionTireTemperature
import de.esolutions.fw.android.comm.asi.sportchronoservice.TemperatureUnit
import de.esolutions.fw.android.comm.asi.sportchronoservice.TiltAngleDisplayAngles
import de.esolutions.fw.android.comm.asi.sportchronoservice.TimeStruct
import de.esolutions.fw.android.comm.asi.sportchronoservice.TireTempLevels
import de.esolutions.fw.android.comm.asi.sportchronoservice.impl.ASISportChronoServiceClientAdapter

/**
 * Real ASI service connector for the SportChronoService.
 * Manages connection lifecycle via [ASISportChronoServiceClientAdapter] and
 * [IServiceCallback] to drive [ConnectionState] transitions.
 *
 * ADR-007: ASI for command/response interactions.
 * ADR-003: Protocol encapsulation behind interfaces.
 */
class SportChronoAsiServiceConnector(
    private val context: Context,
    private val logger: Logger,
) : AsiServiceConnector {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private var clientAdapter: ASISportChronoServiceClientAdapter? = null
    private val asiAdmin by lazy { AsiAdmin.start(context) }
    private val signalFlows = mutableMapOf<String, MutableSharedFlow<SignalValue<Any>>>()

    val currentConnectionState: ConnectionState
        get() = _connectionState.value

    private val serviceCallback =
        object : IServiceCallback {
            override fun connectionEstablished(
                serviceName: String?,
                instanceId: Int,
            ) {
                logger.d(TAG, "ASI service connected: $serviceName")
                _connectionState.value = ConnectionState.CONNECTED
                subscribeToAttributes()
            }

            override fun connectionDisconnected(
                serviceName: String?,
                instanceId: Int,
            ) {
                logger.w(TAG, "ASI service disconnected: $serviceName")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun error(
                serviceName: String?,
                instanceId: Int,
                errorMessage: String?,
            ) {
                logger.e(TAG, "ASI service error: $serviceName — $errorMessage")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

    @Suppress("TooManyFunctions")
    private val serviceListener by lazy {
        object : IASISportChronoServiceServiceListener.Stub() {
            override fun updaterestrictionReason(
                v: Long,
                valid: Boolean,
            ) = Unit

            override fun updatestopwatchState(
                v: EStopwatch_State?,
                valid: Boolean,
            ) = Unit

            override fun updateRaceConditioning(
                v: Boolean,
                valid: Boolean,
            ) = Unit

            override fun updateRaceConditioningReason(
                v: Long,
                valid: Boolean,
            ) {
                val ts = System.currentTimeMillis()
                if (!valid) {
                    logger.w(TAG, "RaceConditioningReason invalid")
                    publishSignal(SIGNAL_RESTRICTION_REASON, SignalValue(0, SignalQuality.INVALID, ts))
                    return
                }
                val reason = v.toInt()
                logger.d(TAG, "RaceConditioningReason received: 0x${reason.toString(16)}")
                publishSignal(SIGNAL_RESTRICTION_REASON, SignalValue(reason, SignalQuality.VALID, ts))
            }

            override fun updateRaceConditioningState(
                v: ERaceConditioning_State?,
                valid: Boolean,
            ) {
                val ts = System.currentTimeMillis()
                if (v == null || !valid) {
                    logger.w(TAG, "RaceConditioningState invalid")
                    publishSignal(SIGNAL_CONDITIONING_STATE, SignalValue(0, SignalQuality.INVALID, ts))
                    return
                }
                val ordinal = v.ordinal
                logger.d(TAG, "RaceConditioningState received: $v (ordinal=$ordinal)")
                publishSignal(SIGNAL_CONDITIONING_STATE, SignalValue(ordinal, SignalQuality.VALID, ts))
            }

            override fun updateRaceConditioningExtended(
                v: RaceConditioningExtended?,
                valid: Boolean,
            ) {
                val ts = System.currentTimeMillis()
                if (v == null || !valid) {
                    logger.w(TAG, "RaceConditioningExtended invalid or null (valid=$valid, data=$v)")
                    val q = SignalQuality.INVALID
                    publishSignal(SIGNAL_MMTR_CURRENT, SignalValue(0.0, q, ts))
                    publishSignal(SIGNAL_MMTR_MAX, SignalValue(0.0, q, ts))
                    publishSignal(SIGNAL_MMTR_MIN, SignalValue(0.0, q, ts))
                    publishSignal(SIGNAL_MMTR_UNIT, SignalValue(0, q, ts))
                    publishSignal(SIGNAL_CONDITIONING_PROGRESS, SignalValue(0, q, ts))
                    return
                }
                logger.d(
                    TAG,
                    "RaceConditioningExtended received: currentTemp=${v.currentTemp}, " +
                        "targetHigh=${v.targetTempHigh}, targetLow=${v.targetTempLow}, " +
                        "unit=${v.tempUnit}, progress=${v.conditioningProgressState}",
                )
                val q = SignalQuality.VALID
                val isCelsius = v.tempUnit.toInt() == TEMP_UNIT_CELSIUS
                val scale = if (isCelsius) CELSIUS_SCALE else FAHRENHEIT_SCALE
                publishSignal(SIGNAL_MMTR_CURRENT, SignalValue(v.currentTemp * scale, q, ts))
                publishSignal(SIGNAL_MMTR_MAX, SignalValue(v.targetTempHigh * scale, q, ts))
                publishSignal(SIGNAL_MMTR_MIN, SignalValue(v.targetTempLow * scale, q, ts))
                publishSignal(SIGNAL_MMTR_UNIT, SignalValue(v.tempUnit.toInt(), q, ts))
                publishSignal(SIGNAL_CONDITIONING_PROGRESS, SignalValue(v.conditioningProgressState.toInt(), q, ts))
            }

            override fun updateRaceConditioningExtendedReason(
                v: Long,
                valid: Boolean,
            ) = Unit

            override fun updateRaceMode(
                v: Boolean,
                valid: Boolean,
            ) = Unit

            override fun updateRaceModeReason(
                v: Long,
                valid: Boolean,
            ) = Unit

            override fun updateRaceModeState(
                v: ERaceMode_State?,
                valid: Boolean,
            ) {
                val ts = System.currentTimeMillis()
                if (v == null || !valid) {
                    logger.w(TAG, "RaceModeState invalid")
                    publishSignal(SIGNAL_RACE_MODE_STATE, SignalValue(0, SignalQuality.INVALID, ts))
                    return
                }
                val ordinal = v.ordinal
                logger.d(TAG, "RaceModeState received: $v (ordinal=$ordinal)")
                publishSignal(SIGNAL_RACE_MODE_STATE, SignalValue(ordinal, SignalQuality.VALID, ts))
            }

            override fun updatelastLapTime(
                v: TimeStruct?,
                valid: Boolean,
            ) = Unit

            override fun updatelastSplitTime(
                v: TimeStruct?,
                valid: Boolean,
            ) = Unit

            override fun updatecoolantTemperature(
                v: Int,
                valid: Boolean,
            ) = Unit

            override fun updatecoolantTemperatureUnit(
                v: TemperatureUnit?,
                valid: Boolean,
            ) = Unit

            override fun updateoilTemperature(
                v: Int,
                valid: Boolean,
            ) = Unit

            override fun updateoilTemperatureUnit(
                v: TemperatureUnit?,
                valid: Boolean,
            ) = Unit

            override fun updateheightInfo(
                v: SuspensionHeightInfo?,
                valid: Boolean,
            ) = Unit

            override fun updateactiveProfile(
                v: Int,
                valid: Boolean,
            ) = Unit

            override fun updatetireTemperature(
                v: SuspensionTireTemperature?,
                valid: Boolean,
            ) = Unit

            override fun updatetireTemperatureUnit(
                v: TemperatureUnit?,
                valid: Boolean,
            ) = Unit

            override fun updatetiltAngleDisplayAngles(
                v: TiltAngleDisplayAngles?,
                valid: Boolean,
            ) = Unit

            override fun updatedamperSettings(
                v: DamperSettings?,
                valid: Boolean,
            ) = Unit

            override fun updatetireTempLevels(
                v: TireTempLevels?,
                valid: Boolean,
            ) = Unit
        }
    }

    private fun subscribeToAttributes() {
        val api = clientAdapter?.api() ?: return
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceConditioning)
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceConditioningState)
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceConditioningReason)
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceConditioningExtended)
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceMode)
        api.setNotification1(ASISportChronoServiceConsts.AttributesIDs.RaceModeState)
        logger.d(TAG, "Subscribed to RaceConditioning and RaceMode ASI attributes")
    }

    override fun connect() {
        logger.d(TAG, "Connecting to ASI SportChronoService")
        _connectionState.value = ConnectionState.CONNECTING

        try {
            val adapter = ASISportChronoServiceClientAdapter(SPORT_CHRONO_INSTANCE_ID, serviceCallback)
            clientAdapter = adapter
            adapter.connectService(serviceListener, asiAdmin)
        } catch (e: SecurityException) {
            logger.e(TAG, "Not permitted to bind to ASI Gateway service", e)
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun disconnect() {
        logger.d(TAG, "Disconnecting from ASI SportChronoService")
        clientAdapter?.disconnectService()
        clientAdapter = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    internal fun getClientAdapter(): ASISportChronoServiceClientAdapter? = clientAdapter

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeSignal(
        signalUrl: String,
    ): Flow<SignalValue<T>> = getOrCreateFlow(signalUrl) as Flow<SignalValue<T>>

    fun hasSignal(signalUrl: String): Boolean = signalUrl in ASI_SIGNAL_URLS

    private fun publishSignal(
        signalUrl: String,
        value: SignalValue<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        getOrCreateFlow(signalUrl).tryEmit(value as SignalValue<Any>)
    }

    private fun getOrCreateFlow(
        signalUrl: String,
    ): MutableSharedFlow<SignalValue<Any>> = signalFlows.getOrPut(signalUrl) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 1)
    }

    companion object {
        private const val TAG = "SportChronoAsiConnector"
        private const val SPORT_CHRONO_INSTANCE_ID = 1
        private const val TEMP_UNIT_CELSIUS = 0
        private const val CELSIUS_SCALE = 0.5
        private const val FAHRENHEIT_SCALE = 1.0

        internal const val SIGNAL_MMTR_CURRENT = "CCL://mmtrCurrentValue"
        internal const val SIGNAL_MMTR_MAX = "CCL://mmtrMaxValue"
        internal const val SIGNAL_MMTR_MIN = "CCL://mmtrMinValue"
        internal const val SIGNAL_MMTR_UNIT = "CCL://mmtrUnit"
        internal const val SIGNAL_CONDITIONING_PROGRESS = "CCL://mmtrConditioningProgress"
        internal const val SIGNAL_CONDITIONING_STATE = "CCL://mmtrConditioningState"
        internal const val SIGNAL_RACE_MODE_STATE = "CCL://mmtrRaceModeState"
        internal const val SIGNAL_RESTRICTION_REASON = "CCL://mmtrRestrictionReason"

        private val ASI_SIGNAL_URLS =
            setOf(
                SIGNAL_MMTR_CURRENT,
                SIGNAL_MMTR_MAX,
                SIGNAL_MMTR_MIN,
                SIGNAL_MMTR_UNIT,
                SIGNAL_CONDITIONING_PROGRESS,
                SIGNAL_CONDITIONING_STATE,
                SIGNAL_RACE_MODE_STATE,
                SIGNAL_RESTRICTION_REASON,
            )
    }
}
