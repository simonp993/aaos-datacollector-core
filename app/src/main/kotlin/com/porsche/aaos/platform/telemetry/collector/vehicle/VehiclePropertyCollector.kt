package com.porsche.aaos.platform.telemetry.collector.vehicle

import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyIds
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ──────────────────────────────────────────────────────────────────────────────
// Available VHAL properties on Scylla (MIB4) emulator — 217 total
//
// ~180 standard AOSP properties (subset of ~350+ defined by VehiclePropertyIds):
//   PERF_VEHICLE_SPEED, PERF_ODOMETER, ENGINE_RPM, GEAR_SELECTION,
//   CURRENT_GEAR, FUEL_LEVEL, FUEL_LEVEL_LOW, EV_BATTERY_LEVEL,
//   EV_CHARGE_PORT_OPEN, EV_CHARGE_PORT_CONNECTED, EV_BATTERY_INSTANTANEOUS_CHARGE_RATE,
//   PARKING_BRAKE_ON, PARKING_BRAKE_AUTO_APPLY, DOOR_LOCK, DOOR_OPEN, DOOR_CHILD_LOCK_ENABLED,
//   HEADLIGHTS_STATE, HEADLIGHTS_SWITCH, HIGH_BEAM_LIGHTS_STATE, HIGH_BEAM_LIGHTS_SWITCH,
//   FOG_LIGHTS_STATE, FOG_LIGHTS_SWITCH, HAZARD_LIGHTS_STATE, HAZARD_LIGHTS_SWITCH,
//   TURN_SIGNAL_STATE, NIGHT_MODE, INFO_VIN, INFO_MAKE, INFO_MODEL, INFO_MODEL_YEAR,
//   INFO_FUEL_TYPE, INFO_EV_CONNECTOR_TYPE, INFO_FUEL_CAPACITY, INFO_FUEL_DOOR_LOCATION,
//   INFO_EV_PORT_LOCATION, INFO_EXTERIOR_DIMENSIONS, INFO_MULTI_EV_PORT_LOCATIONS,
//   HVAC_FAN_SPEED, HVAC_FAN_DIRECTION, HVAC_FAN_DIRECTION_AVAILABLE,
//   HVAC_ACTUAL_FAN_SPEED_RPM, HVAC_TEMPERATURE_SET, HVAC_TEMPERATURE_CURRENT,
//   HVAC_AC_ON, HVAC_MAX_AC_ON, HVAC_DEFROSTER, HVAC_ELECTRIC_DEFROSTER_ON,
//   HVAC_MAX_DEFROST_ON, HVAC_RECIRC_ON, HVAC_AUTO_RECIRC_ON, HVAC_DUAL_ON,
//   HVAC_AUTO_ON, HVAC_POWER_ON, HVAC_SEAT_TEMPERATURE, HVAC_SEAT_VENTILATION,
//   HVAC_STEERING_WHEEL_HEAT, HVAC_SIDE_MIRROR_HEAT, HVAC_TEMPERATURE_DISPLAY_UNITS,
//   HVAC_TEMPERATURE_VALUE_SUGGESTION, SEAT_BELT_BUCKLED, SEAT_OCCUPANCY,
//   TIRE_PRESSURE, DISTANCE_DISPLAY_UNITS, FUEL_VOLUME_DISPLAY_UNITS,
//   CABIN_LIGHTS_STATE, CABIN_LIGHTS_SWITCH, READING_LIGHTS_STATE,
//   READING_LIGHTS_SWITCH, EPOCH_TIME, ANDROID_EPOCH_TIME, IGNITION_STATE,
//   ABS_ACTIVE, TRACTION_CONTROL_ACTIVE, ENV_OUTSIDE_TEMPERATURE,
//   AP_POWER_STATE_REQ, AP_POWER_STATE_REPORT, DISPLAY_BRIGHTNESS,
//   VEHICLE_SPEED_DISPLAY_UNITS, VEHICLE_MAP_SERVICE, OBD2_LIVE_FRAME, etc.
//
// ~37 Porsche vendor-specific properties (0x21* prefix, from vehiclevendorextension JAR):
//   417 constants defined in vendor.porsche.hardware.vehiclevendorextension.VehicleProperty,
//   ~37 registered on emulator. Categories include:
//   - Power/State: PORSCHE_AP_OPERATING_MODE, PORSCHE_CLAMPS_STATE, PORSCHE_SHUTDOWN_FLAG
//   - Telephony: PORSCHE_CALL_STATE, PORSCHE_ANDROID_CALL_STATE, INT_TELEPHONY_STATE
//   - Network: PORSCHE_NETWORK_PROVIDER, PORSCHE_NETWORK_TYPE, PORSCHE_NETWORK_SIGNAL_QUALITY
//   - EV: PORSCHE_EV_ENERGY_RANGE, PORSCHE_EV_CURRENT_BATTERY_SOC, etc. (~100+ defined)
//   - Diagnostics: PORSCHE_DIAG_CAR_CLASS, PORSCHE_DIAG_MMTR_AVAILABLE, etc. (~200+ defined)
//   - Cluster: PORSCHE_CLUSTER_NIGHT_MODE, PORSCHE_LANE_0..4
//   - Other: PORSCHE_KAM_RESOURCE_STATE, PORSCHE_BEM_WARNING, PORSCHE_MAP_MATCHED_POSITION
// ──────────────────────────────────────────────────────────────────────────────

class VehiclePropertyCollector @Inject constructor(
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "VehicleProperty"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    private val previousValues = ConcurrentHashMap<Int, Any>()
    private val changeBatch = mutableListOf<List<Any?>>()
    private val batchMutex = Mutex()
    private var flushJob: Job? = null

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting VHAL observation (batched every ${FLUSH_INTERVAL_MS / 1000}s)")

        // Start periodic flush job
        flushJob = CoroutineScope(Dispatchers.Default).launch {
            delay(STAGGER_DELAY_MS)
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBatch()
            }
        }

        coroutineScope {
            OBSERVED_PROPERTIES.forEach { (propertyId, propertyName) ->
                launch {
                    vhalPropertyService.observeProperty<Any>(propertyId)
                        .catch { e ->
                            logger.w(TAG, "Failed to observe $propertyName ($propertyId)", e)
                        }
                        .collect { value ->
                            if (!running) return@collect
                            val previous = previousValues.put(propertyId, value)
                            if (value == previous) return@collect

                            // Append to batch: [timestamp, property, previous, current]
                            val entry = listOf(
                                System.currentTimeMillis(),
                                propertyName,
                                previous,
                                value,
                            )
                            batchMutex.withLock { changeBatch.add(entry) }
                        }
                }
            }
        }
    }

    override fun stop() {
        running = false
        flushJob?.cancel()
        flushJob = null
        previousValues.clear()
        logger.i(TAG, "Stopped")
    }

    private suspend fun flushBatch() {
        val snapshot = batchMutex.withLock {
            if (changeBatch.isEmpty()) return
            changeBatch.toList().also { changeBatch.clear() }
        }

        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "VHAL_ValuesChanged",
                    "trigger" to "heartbeat",
                    "metadata" to mapOf(
                        "count" to snapshot.size,
                        "sampleSchema" to listOf("timestampMillis", "property", "previous", "current"),
                        "changes" to snapshot,
                    ),
                ),
            ),
        )
        logger.d(TAG, "Flushed ${snapshot.size} VHAL changes")
    }

    companion object {
        private const val TAG = "VehiclePropertyCollector"
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val STAGGER_DELAY_MS = 6_000L

        private val OBSERVED_PROPERTIES = listOf(
            // Driving / Powertrain
            VhalPropertyIds.PERF_VEHICLE_SPEED to "PERF_VEHICLE_SPEED",
            VhalPropertyIds.ENGINE_RPM to "ENGINE_RPM",
            VhalPropertyIds.GEAR_SELECTION to "GEAR_SELECTION",
            VhalPropertyIds.CURRENT_GEAR to "CURRENT_GEAR",
            VhalPropertyIds.FUEL_LEVEL to "FUEL_LEVEL",
            VhalPropertyIds.FUEL_LEVEL_LOW to "FUEL_LEVEL_LOW",
            VhalPropertyIds.EV_BATTERY_LEVEL to "EV_BATTERY_LEVEL",
            VhalPropertyIds.IGNITION_STATE to "IGNITION_STATE",
            VhalPropertyIds.PARKING_BRAKE_ON to "PARKING_BRAKE_ON",
            // Body / Exterior
            VhalPropertyIds.DOOR_LOCK to "DOOR_LOCK",
            VhalPropertyIds.HEADLIGHTS_STATE to "HEADLIGHTS_STATE",
            VhalPropertyIds.TURN_SIGNAL_STATE to "TURN_SIGNAL_STATE",
            VhalPropertyIds.NIGHT_MODE to "NIGHT_MODE",
            VhalPropertyIds.TIRE_PRESSURE to "TIRE_PRESSURE",
            // Cabin / Comfort
            VhalPropertyIds.ENV_OUTSIDE_TEMPERATURE to "ENV_OUTSIDE_TEMPERATURE",
            VhalPropertyIds.DISPLAY_BRIGHTNESS to "DISPLAY_BRIGHTNESS",
            VhalPropertyIds.CABIN_LIGHTS_STATE to "CABIN_LIGHTS_STATE",
            VhalPropertyIds.SEAT_BELT_BUCKLED to "SEAT_BELT_BUCKLED",
            VhalPropertyIds.SEAT_OCCUPANCY to "SEAT_OCCUPANCY",
            VhalPropertyIds.ABS_ACTIVE to "ABS_ACTIVE",
            VhalPropertyIds.TRACTION_CONTROL_ACTIVE to "TRACTION_CONTROL_ACTIVE",
            // HVAC / Climate
            VhalPropertyIds.HVAC_FAN_SPEED to "HVAC_FAN_SPEED",
            VhalPropertyIds.HVAC_FAN_DIRECTION to "HVAC_FAN_DIRECTION",
            VhalPropertyIds.HVAC_FAN_DIRECTION_AVAILABLE to "HVAC_FAN_DIRECTION_AVAILABLE",
            VhalPropertyIds.HVAC_ACTUAL_FAN_SPEED_RPM to "HVAC_ACTUAL_FAN_SPEED_RPM",
            VhalPropertyIds.HVAC_TEMPERATURE_SET to "HVAC_TEMPERATURE_SET",
            VhalPropertyIds.HVAC_TEMPERATURE_CURRENT to "HVAC_TEMPERATURE_CURRENT",
            VhalPropertyIds.HVAC_AC_ON to "HVAC_AC_ON",
            VhalPropertyIds.HVAC_MAX_AC_ON to "HVAC_MAX_AC_ON",
            VhalPropertyIds.HVAC_DEFROSTER to "HVAC_DEFROSTER",
            VhalPropertyIds.HVAC_ELECTRIC_DEFROSTER_ON to "HVAC_ELECTRIC_DEFROSTER_ON",
            VhalPropertyIds.HVAC_MAX_DEFROST_ON to "HVAC_MAX_DEFROST_ON",
            VhalPropertyIds.HVAC_RECIRC_ON to "HVAC_RECIRC_ON",
            VhalPropertyIds.HVAC_AUTO_RECIRC_ON to "HVAC_AUTO_RECIRC_ON",
            VhalPropertyIds.HVAC_DUAL_ON to "HVAC_DUAL_ON",
            VhalPropertyIds.HVAC_AUTO_ON to "HVAC_AUTO_ON",
            VhalPropertyIds.HVAC_POWER_ON to "HVAC_POWER_ON",
            VhalPropertyIds.HVAC_SEAT_TEMPERATURE to "HVAC_SEAT_TEMPERATURE",
            VhalPropertyIds.HVAC_SEAT_VENTILATION to "HVAC_SEAT_VENTILATION",
            VhalPropertyIds.HVAC_STEERING_WHEEL_HEAT to "HVAC_STEERING_WHEEL_HEAT",
            VhalPropertyIds.HVAC_SIDE_MIRROR_HEAT to "HVAC_SIDE_MIRROR_HEAT",
            VhalPropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS to "HVAC_TEMPERATURE_DISPLAY_UNITS",
            VhalPropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION to "HVAC_TEMPERATURE_VALUE_SUGGESTION",
            // Power / Porsche vendor (available on real hardware, gracefully skipped on emulator)
            VhalPropertyIds.PORSCHE_AP_OPERATING_MODE to "PORSCHE_AP_OPERATING_MODE",
            VhalPropertyIds.PORSCHE_CLAMPS_STATE to "PORSCHE_CLAMPS_STATE",
            VhalPropertyIds.PORSCHE_SHUTDOWN_FLAG to "PORSCHE_SHUTDOWN_FLAG",
        )
    }
}
