package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import de.eso.car.services.powermanagement.DisplayStateCallbackData
import de.eso.car.services.powermanagement.DisplayStateCallbackRegistrationData
import de.eso.car.services.powermanagement.IDisplayStateCallback
import de.eso.car.services.powermanagement.IPowermanagementStandbyService
import de.eso.car.services.powermanagement.IStandbyModeCallback
import de.eso.car.services.powermanagement.StandbyMode
import de.eso.car.services.powermanagement.StandbyModeCallbackData
import de.eso.car.services.powermanagement.StandbyModeCallbackRegistrationData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes display standby state via the EsoCarStandbyService AIDL interface.
 *
 * Binds to action [ACTION_STANDBY_SERVICE] which requires
 * permission `de.eso.car.permission.POWERMANAGEMENT_STANDBY` (signature|privileged).
 *
 * Registers callbacks for:
 * 1. Standby on/off state (active/inactive) per display
 * 2. Standby mode changes (vehiclemodel/media) per display
 */
class AidlDisplayStandbySource(
    private val context: Context,
    private val logger: Logger,
) : DisplayStandbySource {

    private var service: IPowermanagementStandbyService? = null
    private var bound = false
    private val currentModes = ConcurrentHashMap<String, String>()

    override fun observeStandbyState(): Flow<DisplayStandbyEvent> = callbackFlow {
        val deathBinder = Binder()

        val stateCallback = object : IDisplayStateCallback.Stub() {
            override fun onUpdateDisplayState(data: DisplayStateCallbackData) {
                val display = mapDisplayName(data.displayName)
                val mode = currentModes[display]
                val event = DisplayStandbyEvent(
                    display = display,
                    active = data.requestedStateActive,
                    mode = mode,
                    disabled = data.requestedStateDisabled,
                    timestamp = System.currentTimeMillis(),
                )
                logger.d(TAG, "Standby state: $display active=${data.requestedStateActive}" +
                    " disabled=${data.requestedStateDisabled} mode=$mode")
                trySend(event)
            }
        }

        val modeCallback = object : IStandbyModeCallback.Stub() {
            override fun onUpdateStandbyMode(data: StandbyModeCallbackData) {
                val display = mapDisplayName(data.displayName)
                val modeName = mapStandbyMode(data.activeStandbyMode)
                currentModes[display] = modeName
                logger.d(TAG, "Standby mode: $display → $modeName" +
                    " (available=${data.availableStandbyModes?.map { mapStandbyMode(it) }})")
                // Emit a mode-change event — use active=true as a signal that
                // the mode changed; the collector decides the effective state
                val event = DisplayStandbyEvent(
                    display = display,
                    active = true,
                    mode = modeName,
                    disabled = false,
                    modeChangeOnly = true,
                    timestamp = System.currentTimeMillis(),
                )
                trySend(event)
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPowermanagementStandbyService.Stub.asInterface(binder)
                logger.i(TAG, "Connected to EsoCarStandbyService")

                try {
                    // Register standby on/off callback for PID
                    val stateRegData = DisplayStateCallbackRegistrationData().apply {
                        displayName = DISPLAY_NAME_PID
                        callerId = CALLER_ID
                        displayStateCallback = stateCallback
                    }
                    service?.registerDisplayStandbyOnOffStateCallback(stateRegData, deathBinder)

                    // Register standby mode callback for PID
                    val modeRegData = StandbyModeCallbackRegistrationData().apply {
                        displayName = DISPLAY_NAME_PID
                        callerId = CALLER_ID
                        standbyModeCallback = modeCallback
                    }
                    service?.registerDisplayStandbyModeCallback(modeRegData, deathBinder)
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to register standby callbacks: ${e.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                logger.w(TAG, "Disconnected from EsoCarStandbyService")
            }
        }

        val intent = Intent(ACTION_STANDBY_SERVICE).apply {
            setPackage(PACKAGE_SYSTEM_UI)
        }

        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                logger.w(TAG, "Could not bind to EsoCarStandbyService")
            }
        } catch (e: SecurityException) {
            logger.e(TAG, "Permission denied binding to StandbyService: ${e.message}")
        }

        awaitClose {
            if (bound) {
                try {
                    context.unbindService(connection)
                } catch (e: Exception) {
                    logger.w(TAG, "Error unbinding StandbyService: ${e.message}")
                }
                bound = false
            }
            service = null
        }
    }

    override fun start() {
        logger.d(TAG, "DisplayStandbySource started")
    }

    override fun stop() {
        currentModes.clear()
        logger.d(TAG, "DisplayStandbySource stopped")
    }

    companion object {
        private const val TAG = "AidlDisplayStandbySource"
        private const val ACTION_STANDBY_SERVICE =
            "de.eso.car.intent.powermanagement.StandbyService"
        private const val PACKAGE_SYSTEM_UI = "com.android.systemui"
        private const val CALLER_ID = "com.porsche.aaos.datacollector.core#DisplayStandby"
        private const val DISPLAY_NAME_PID = "PID"

        fun mapDisplayName(aidlName: String): String = when (aidlName) {
            "PID" -> "passenger"
            "CID" -> "center"
            "FOND" -> "rear"
            else -> aidlName.lowercase()
        }

        fun mapStandbyMode(mode: Byte): String = when (mode) {
            StandbyMode.VEHICLEMODEL -> "vehiclemodel"
            StandbyMode.MEDIA -> "media"
            StandbyMode.MYSCREEN -> "myscreen"
            StandbyMode.DRIVEMODE -> "drivemode"
            else -> "unknown"
        }
    }
}
