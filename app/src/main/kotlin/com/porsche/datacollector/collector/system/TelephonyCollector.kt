package com.porsche.datacollector.collector.system

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@Suppress("DEPRECATION") // PhoneStateListener is deprecated in API 31+ but TelephonyCallback requires API 31
class TelephonyCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "Telephony"

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override suspend fun start() {
        logger.i(TAG, "Starting telephony monitoring")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager = tm

        // Emit initial state
        sendTelephonyState(tm)

        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                telemetry.send(
                    TelemetryEvent(
                        eventId = "telephony.call_state",
                        payload = mapOf(
                            "state" to state,
                            "label" to callStateLabel(state),
                        ),
                    ),
                )
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                telemetry.send(
                    TelemetryEvent(
                        eventId = "telephony.signal",
                        payload = mapOf(
                            "level" to (signalStrength?.level ?: -1),
                        ),
                    ),
                )
            }
        }
        phoneStateListener = listener

        tm.listen(
            listener,
            PhoneStateListener.LISTEN_CALL_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS,
        )
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
                eventId = "telephony.state",
                payload = mapOf(
                    "simState" to tm.simState,
                    "networkOperator" to tm.networkOperatorName,
                    "isNetworkRoaming" to tm.isNetworkRoaming,
                    "dataNetworkType" to tm.dataNetworkType,
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

    companion object {
        private const val TAG = "TelephonyCollector"
    }
}
