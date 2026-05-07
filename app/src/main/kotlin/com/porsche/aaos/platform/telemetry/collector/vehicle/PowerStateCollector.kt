package com.porsche.aaos.platform.telemetry.collector.vehicle

import android.car.Car
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.power.CarPowerManager
import android.content.Context
import android.os.SystemClock
import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyIds
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Collects power lifecycle events for the vehicle infotainment system.
 *
 * Events emitted:
 * - Power_Boot: Once at startup with boot reason, ignition state, uptime, power policy
 * - Power_StateChanged: On CarPowerManager state transitions (shutdown-prepare, suspend, etc.)
 *
 * IGNITION_STATE changes are handled by VehiclePropertyCollector (avoids duplication).
 * Porsche vendor power properties (PORSCHE_AP_OPERATING_MODE, PORSCHE_CLAMPS_STATE,
 * PORSCHE_SHUTDOWN_FLAG) are also observed by VehiclePropertyCollector when available on
 * real hardware.
 *
 * Lifecycle stages:
 * 1. Pre-ignition wake: MCU boots Android (AP_POWER_BOOTUP_REASON = DOOR_UNLOCK/APPROACH)
 *    IGNITION_STATE = OFF or ACC, reduced power policy
 * 2. Ignition ON (KL15): IGNITION_STATE → ON/START, full power policy
 * 3. Shutdown/Suspend: CarPowerManager signals SHUTDOWN_PREPARE → SUSPEND_ENTER or SHUTDOWN_ENTER
 *    Garage Mode may run before actual suspend-to-RAM or full shutdown
 */
class PowerStateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "PowerState"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private var car: Car? = null
    private var powerManager: CarPowerManager? = null

    override suspend fun start() {
        logger.i(TAG, "Starting power state monitoring")

        // Emit boot event with initial state
        emitBootEvent()

        // Connect to CarPowerManager for shutdown/suspend signals
        connectCarPowerManager()
    }

    override fun stop() {
        car?.disconnect()
        car = null
        powerManager = null
        logger.i(TAG, "Stopped")
    }

    private fun emitBootEvent() {
        val bootupReason = readBootupReason()
        val ignitionState = readIgnitionState()
        val uptimeMs = SystemClock.elapsedRealtime()
        val currentPowerPolicy = readCurrentPowerPolicy()

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "Power_Boot",
                    "trigger" to "system",
                    "metadata" to mapOf(
                        "bootupReason" to bootupReasonName(bootupReason),
                        "bootupReasonCode" to bootupReason,
                        "ignitionState" to ignitionStateName(ignitionState),
                        "ignitionStateCode" to ignitionState,
                        "uptimeMs" to uptimeMs,
                        "powerPolicy" to currentPowerPolicy,
                    ),
                ),
            ),
        )
        logger.i(
            TAG,
            "Boot event: reason=${bootupReasonName(bootupReason)}, " +
                "ignition=${ignitionStateName(ignitionState)}, uptime=${uptimeMs}ms",
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun connectCarPowerManager() {
        try {
            val carInstance = Car.createCar(context) ?: run {
                logger.w(TAG, "Car.createCar returned null")
                return
            }
            car = carInstance
            val pm = carInstance.getCarManager(Car.POWER_SERVICE) as? CarPowerManager
            powerManager = pm

            if (pm == null) {
                logger.w(TAG, "CarPowerManager not available")
                return
            }

            pm.setListener(context.mainExecutor) { state ->
                val stateName = powerStateName(state)
                telemetry.send(
                    TelemetryEvent(
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "Power_StateChanged",
                            "trigger" to "system",
                            "metadata" to mapOf(
                                "state" to stateName,
                                "stateCode" to state,
                            ),
                        ),
                    ),
                )
                logger.i(TAG, "Power state changed: $stateName ($state)")
            }
            logger.i(TAG, "CarPowerManager listener registered")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to connect CarPowerManager: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readBootupReason(): Int {
        return try {
            vhalPropertyService.readProperty<Int>(VehiclePropertyIds.AP_POWER_BOOTUP_REASON) ?: -1
        } catch (e: Exception) {
            logger.d(TAG, "Could not read AP_POWER_BOOTUP_REASON: ${e.message}")
            -1
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readIgnitionState(): Int {
        return try {
            vhalPropertyService.readProperty<Int>(VhalPropertyIds.IGNITION_STATE) ?: 0
        } catch (e: Exception) {
            logger.d(TAG, "Could not read IGNITION_STATE: ${e.message}")
            0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readCurrentPowerPolicy(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("dumpsys", "car_service", "--services", "CarPowerManagementService"),
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("mCurrentPowerPolicyId:\\s*(\\S+)").find(output)
            match?.groupValues?.get(1) ?: "unknown"
        } catch (e: Exception) {
            logger.d(TAG, "Could not read power policy: ${e.message}")
            "unknown"
        }
    }

    companion object {
        private const val TAG = "PowerStateCollector"

        private fun ignitionStateName(state: Int?): String = when (state) {
            VehicleIgnitionState.UNDEFINED -> "UNDEFINED"
            VehicleIgnitionState.LOCK -> "LOCK"
            VehicleIgnitionState.OFF -> "OFF"
            VehicleIgnitionState.ACC -> "ACC"
            VehicleIgnitionState.ON -> "ON"
            VehicleIgnitionState.START -> "START"
            else -> "UNKNOWN($state)"
        }

        private fun bootupReasonName(reason: Int): String = when (reason) {
            1 -> "USER_POWER_ON"
            2 -> "DOOR_UNLOCK"
            3 -> "TIMER"
            4 -> "DOOR_OPEN"
            5 -> "APPROACH"
            else -> "UNKNOWN($reason)"
        }

        @Suppress("CyclomaticComplexMethod")
        private fun powerStateName(state: Int): String = when (state) {
            CarPowerManager.STATE_INVALID -> "INVALID"
            CarPowerManager.STATE_WAIT_FOR_VHAL -> "WAIT_FOR_VHAL"
            CarPowerManager.STATE_SUSPEND_ENTER -> "SUSPEND_ENTER"
            CarPowerManager.STATE_SUSPEND_EXIT -> "SUSPEND_EXIT"
            CarPowerManager.STATE_SHUTDOWN_ENTER -> "SHUTDOWN_ENTER"
            CarPowerManager.STATE_ON -> "ON"
            CarPowerManager.STATE_SHUTDOWN_PREPARE -> "SHUTDOWN_PREPARE"
            CarPowerManager.STATE_SHUTDOWN_CANCELLED -> "SHUTDOWN_CANCELLED"
            CarPowerManager.STATE_HIBERNATION_ENTER -> "HIBERNATION_ENTER"
            CarPowerManager.STATE_HIBERNATION_EXIT -> "HIBERNATION_EXIT"
            CarPowerManager.STATE_PRE_SHUTDOWN_PREPARE -> "PRE_SHUTDOWN_PREPARE"
            CarPowerManager.STATE_POST_SUSPEND_ENTER -> "POST_SUSPEND_ENTER"
            CarPowerManager.STATE_POST_SHUTDOWN_ENTER -> "POST_SHUTDOWN_ENTER"
            CarPowerManager.STATE_POST_HIBERNATION_ENTER -> "POST_HIBERNATION_ENTER"
            else -> "UNKNOWN($state)"
        }
    }
}
