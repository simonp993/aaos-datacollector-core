package com.porsche.aaos.platform.telemetry.collector.system

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION") // PhoneStateListener is deprecated in API 31+ but TelephonyCallback requires API 31
class TelephonyCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Telephony"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    // Previous state tracking for prev/current payloads
    private var previousCallState: Map<String, Any>? = null
    private var previousSignalLevel: Map<String, Any>? = null

    override suspend fun start() {
        logger.i(TAG, "Starting telephony monitoring")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm

        // Emit initial state
        sendTelephonyState(tm)

        // PhoneStateListener must be created on a thread with a Looper
        withContext(Dispatchers.Main) {
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    val current = mapOf(
                        "state" to state,
                        "label" to callStateLabel(state),
                    )
                    if (current == previousCallState) return
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "Telephony_CallStateChanged",
                                "trigger" to "system",
                                "metadata" to mapOf(
                                    "previous" to previousCallState,
                                    "current" to current,
                                ),
                            ),
                        ),
                    )
                    previousCallState = current
                }

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    val current = mapOf(
                        "level" to (signalStrength?.level ?: -1),
                    )
                    if (current == previousSignalLevel) return
                    telemetry.send(
                        TelemetryEvent(
                            signalId = signalId,
                            payload = mapOf(
                                "actionName" to "Telephony_SignalStrengthChanged",
                                "trigger" to "system",
                                "metadata" to mapOf(
                                    "previous" to previousSignalLevel,
                                    "current" to current,
                                ),
                            ),
                        ),
                    )
                    previousSignalLevel = current
                }
            }
            phoneStateListener = listener

            tm.listen(
                listener,
                PhoneStateListener.LISTEN_CALL_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS,
            )
        }
    }

    override fun stop() {
        phoneStateListener?.let { listener ->
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
        telephonyManager = null
        logger.i(TAG, "Stopped")
    }

    private fun sendTelephonyState(tm: TelephonyManager) {
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Telephony_InitialState",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "simState" to tm.simState,
                        "simStateLabel" to simStateLabel(tm.simState),
                        "networkOperator" to tm.networkOperatorName,
                        "isNetworkRoaming" to tm.isNetworkRoaming,
                        "dataNetworkType" to tm.dataNetworkType,
                    ),
                ),
            ),
        )
    }

    private fun callStateLabel(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "ACTIVE"
        else -> "UNKNOWN($state)"
    }

    private fun simStateLabel(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        else -> "UNKNOWN($state)"
    }

    companion object {
        private const val TAG = "TelephonyCollector"
    }
}
