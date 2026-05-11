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
// Available VHAL properties on Scylla (MIB4) emulator — 662 total
// Queried 2026-05-11 via: adb shell cmd car_service get-carpropertyconfig
// Resolved via: vendor.porsche.hardware.vehiclevendorextension.VehicleProperty
//
// Legend: [R]=READ [RW]=READ_WRITE [W]=WRITE
//   STATIC   = read once at boot, never changes
//   ON_CHANGE = event-driven, fires only when value changes
//   CONTINUOUS(maxHz) = polled at up to maxHz sample rate
//
// ── AOSP Standard Properties (125) ─────────────────────────────────────────
//
// Vehicle Info (STATIC)
//   INFO_VIN                    STRING  [R]   e.g. "WP0ZZZY14SSA33012"
//   INFO_MAKE                   STRING  [R]   e.g. "Porsche"
//   INFO_MODEL                  STRING  [R]   e.g. "PO5134"
//   INFO_MODEL_YEAR             INT32   [R]   e.g. 2025
//   INFO_FUEL_TYPE              INT32[] [R]   e.g. [10] (ELECTRIC)
//   INFO_EV_CONNECTOR_TYPE      INT32[] [R]   e.g. [10,11]
//   INFO_EV_BATTERY_CAPACITY    FLOAT   [R]   e.g. 79450.0 Wh
//   INFO_FUEL_CAPACITY          FLOAT   [R]   e.g. 0.0 mL (EV = no fuel)
//   INFO_FUEL_DOOR_LOCATION     INT32   [R]   e.g. 4
//   INFO_EV_PORT_LOCATION       INT32   [R]   e.g. 1 (FRONT_LEFT)
//   INFO_MULTI_EV_PORT_LOCATIONS INT32[] [R]  e.g. [1,4]
//   INFO_EXTERIOR_DIMENSIONS    INT32[] [R]   e.g. [1776,4950,2008,2140,...]
//   INFO_DRIVER_SEAT            INT32   [R]   SEAT area, e.g. 1
//
// Driving / Powertrain (CONTINUOUS unless noted)
//   PERF_VEHICLE_SPEED          FLOAT   [R]   max 10Hz, m/s, e.g. 0.0
//   PERF_VEHICLE_SPEED_DISPLAY  FLOAT   [R]   max 10Hz, display units, 15000.0
//   PERF_ODOMETER               FLOAT   [R]   max 10Hz, km, e.g. 23402.0
//   PERF_STEERING_ANGLE         FLOAT   [R]   max 10Hz, deg, e.g. 0.0
//   PERF_REAR_STEERING_ANGLE    FLOAT   [R]   max 10Hz, deg, e.g. 0.0
//   ENGINE_RPM                  FLOAT   [R]   max 10Hz, rpm, e.g. 0.0
//   ENGINE_OIL_TEMP             FLOAT   [R]   max 10Hz, °C, e.g. 101.0
//   ENGINE_OIL_LEVEL            INT32   [R]   ON_CHANGE, e.g. 2
//   GEAR_SELECTION              INT32   [R]   ON_CHANGE, e.g. 4 (DRIVE)
//   CURRENT_GEAR                INT32   [R]   ON_CHANGE, e.g. 4
//   IGNITION_STATE              INT32   [R]   ON_CHANGE, e.g. 4 (START)
//   WHEEL_TICK                  INT64[] [R]   max 10Hz, tick counts
//
// Fuel / EV / Range
//   FUEL_LEVEL                  FLOAT   [R]   max 100Hz, mL, e.g. 15000.0
//   FUEL_LEVEL_LOW              BOOL    [R]   ON_CHANGE, e.g. false
//   EV_BATTERY_LEVEL            FLOAT   [R]   max 100Hz, Wh, e.g. 150000.0
//   EV_CHARGE_STATE             INT32   [R]   ON_CHANGE, e.g. 3
//   EV_CHARGE_PORT_OPEN         BOOL    [R]   ON_CHANGE, e.g. false
//   EV_CHARGE_PORT_CONNECTED    BOOL    [R]   ON_CHANGE, e.g. false
//   EV_BATTERY_INSTANT_CHARGE   FLOAT   [R]   max 10Hz, W, e.g. 0.0
//   EV_CURRENT_BATTERY_CAPACITY FLOAT   [R]   ON_CHANGE, Wh, e.g. 18950.0
//   EV_CHARGE_TIME_REMAINING    INT32   [R]   max 2Hz, sec, e.g. 0
//   EV_REGENERATIVE_BRAKING     INT32   [R]   ON_CHANGE, e.g. 0
//   RANGE_REMAINING             FLOAT   [R]   max 2Hz, m, e.g. 110000.0
//
// Body / Exterior / Lights (ON_CHANGE)
//   DOOR_LOCK                   BOOL    [RW]  DOOR area, e.g. true
//   WINDOW_POS                  INT32   [RW]  WINDOW area, e.g. 0 (closed)
//   HEADLIGHTS_STATE            INT32   [R]   e.g. 1 (ON)
//   HIGH_BEAM_LIGHTS_STATE      INT32   [R]   e.g. 1
//   HAZARD_LIGHTS_STATE         INT32   [R]   e.g. 1
//   REAR_FOG_LIGHTS_STATE       INT32   [R]   e.g. 0 (OFF)
//   NIGHT_MODE                  BOOL    [R]   e.g. true
//   PARKING_BRAKE_ON            BOOL    [R]   e.g. true
//   PARKING_BRAKE_AUTO_APPLY    BOOL    [R]   e.g. true
//   VEHICLE_IN_USE              BOOL    [RW]  e.g. true
//
// Cabin / Comfort / Safety (ON_CHANGE unless noted)
//   ENV_OUTSIDE_TEMPERATURE     FLOAT   [R]   CONTINUOUS max 2Hz, °C, 23.0
//   DISPLAY_BRIGHTNESS          INT32   [RW]  0-100, e.g. 100
//   CABIN_LIGHTS_STATE          INT32   [R]   e.g. 0 (OFF)
//   SEAT_BELT_BUCKLED           BOOL    [R]   SEAT area, e.g. false
//   SEAT_OCCUPANCY              INT32   [R]   SEAT area, e.g. 2 (OCCUPIED)
//   TIRE_PRESSURE               FLOAT   [R]   CONTINUOUS max 2Hz, WHEEL, kPa
//   ABS_ACTIVE                  BOOL    [R]   e.g. false
//   TRACTION_CONTROL_ACTIVE     BOOL    [R]   e.g. false
//   ELECTRONIC_STABILITY_CTRL   INT32   [R]   e.g. 1 (ENABLED)
//
// HVAC / Climate (ON_CHANGE, SEAT area — most NOT on emulator, real HW only)
//   HVAC_AUTO_ON                BOOL    [R]   e.g. true   ← emulator ✓
//   HVAC_TEMPERATURE_DISPLAY    INT32   [R]   e.g. 48     ← emulator ✓
//   HVAC_TEMP_VALUE_SUGGESTION  FLOAT[] [RW]  e.g. 66.2   ← emulator ✓
//   HVAC_FAN_SPEED .. HVAC_SIDE_MIRROR_HEAT — 20 props, real HW only
//
// ADAS / Driver Monitoring (ON_CHANGE)
//   AUTO_EMERGENCY_BRAKING      INT32   [R]   e.g. 1 (ENABLED)
//   FORWARD_COLLISION_WARNING   INT32   [R]   e.g. 1 (ENABLED)
//   LANE_DEPARTURE_WARNING      INT32   [R]   e.g. 1 (ENABLED)
//   LANE_KEEP_ASSIST_STATE      INT32   [R]   e.g. 0 (DISABLED)
//   DRIVER_DROWSINESS_STATE     INT32   [R]   e.g. 0
//   DRIVER_DISTRACTION_STATE    INT32   [R]   e.g. 0
//   HANDS_ON_DETECTION_STATE    INT32   [R]   e.g. 2
//
// System / Power / Time (ON_CHANGE)
//   AP_POWER_STATE_REQ          INT32[] [R]   e.g. [0,0]
//   AP_POWER_STATE_REPORT       INT32[] [RW]  e.g. [1,0]
//   EXTERNAL_CAR_TIME           INT64   [R]   epoch ms, e.g. 1778479041000
//   VEHICLE_SPEED_DISPLAY_UNITS INT32   [R]   e.g. 145 (KM_PER_HOUR)
//   DISTANCE_DISPLAY_UNITS      INT32   [R]   e.g. 35
//   VEHICLE_CURB_WEIGHT         INT32   [R]   STATIC, e.g. 100000
//   VEHICLE_MAP_SERVICE         MIXED   [RW]
//   CLUSTER_NAVIGATION_STATE    BYTES   [W]
//   OBD2_LIVE_FRAME             MIXED   [R]
//   + 25 more (user mgmt, power policy, watchdog, etc.)
//
// ── Porsche Vendor Properties (537, prefix 0x21*) ──────────────────────────
// Source: vehiclevendorextension JAR (417 named constants)
//   ~440 resolved by name, ~97 UNKNOWN (not in JAR, possibly newer FW)
//
// Power / State (ON_CHANGE)
//   PORSCHE_AP_OPERATING_MODE   INT32   [R]   e.g. 6
//   PORSCHE_CLAMPS_STATE        INT32   [R]   e.g. 1
//   PORSCHE_SHUTDOWN_FLAG       BOOL    [R]
//   PORSCHE_WAKE_UP_REASON      INT32   [R]   e.g. 1
//   PORSCHE_CLUSTER_NIGHT_MODE  BOOL    [R]   e.g. true
//   PORSCHE_BEM_WARNING         INT32   [R]   e.g. 0
//
// Network / Connectivity (ON_CHANGE)
//   PORSCHE_NETWORK_PROVIDER    STRING  [R]   e.g. ""
//   PORSCHE_NETWORK_TYPE        STRING  [R]   e.g. "4.5G"
//   PORSCHE_NETWORK_SIGNAL_QUALITY INT32 [R]  e.g. 3 (0-5 scale)
//
// EV Energy / Range (~100 props, ON_CHANGE)
//   PORSCHE_EV_ENERGY_RANGE     FLOAT   [R]   e.g. 189.5 km
//   PORSCHE_EV_MAX_ENERGY_RANGE FLOAT   [R]   e.g. 794.5 km
//   PORSCHE_EV_CURRENT_BATTERY_SOC FLOAT [R]  e.g. 27.0%
//   PORSCHE_EV_CURRENT_RANGES_PRIMARY FLOAT [R] e.g. 110.0 km
//   PORSCHE_EV_CURRENT_RANGES_UNIT STRING [R] e.g. "km"
//   PORSCHE_EV_REMAINING_CHARGE_TIME FLOAT [R]
//   + consumption curves, street-class data, charging curves, etc.
//
// Diagnostics (~200 props, mostly ON_CHANGE, [RW])
//   PORSCHE_DIAG_CAR_CLASS      INT32   [R]   STATIC, e.g. 5
//   PORSCHE_DIAG_CAR_BODYSTYLE  INT32   [R]   STATIC, e.g. 3
//   PORSCHE_DIAG_VARIANT_STRING STRING  [R]   "FM3-P-S-101-NDCWB6-..."
//   PORSCHE_DIAG_SOFTWARE_VERSION STRING [R]  e.g. "X047"
//   PORSCHE_DIAG_COUNTRY_CODE_HMI STRING [RW] e.g. "DE"
//   PORSCHE_DIAG_MMTR_AVAILABLE INT32   [RW]  e.g. 1
//   + bluetooth, USB, GNSS, radio, display tests, DTCs, etc.
//
// Telephony (ON_CHANGE)
//   PORSCHE_CALL_STATE           INT32  [R]
//   PORSCHE_ANDROID_CALL_STATE   INT32  [RW]
//   INT_TELEPHONY_STATE          INT32[] [RW]
//
// Cluster / HMI
//   PORSCHE_LANE_0..4            MIXED  [RW]  lane topology data
//   PORSCHE_TBF_SHORTCUTS        INT32[] [R]
//   PORSCHE_MAP_MATCHED_POSITION INT64[] [W]
//
// Other
//   PORSCHE_INFO_ECU_SERIAL_NUMBER STRING [R] e.g. "KRK-00007..."
//   PORSCHE_KAM_RESOURCE_STATE   INT32[] [RW]
//   PORSCHE_TIME_CBOX            INT64  [R]   epoch ms
//   SEAT_MASSAGE_PROGRAM         STRING [RW]  e.g. "frontR_massageProgram1"
//   + ~97 UNKNOWN_0x21* properties (unnamed in vendor JAR)
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
            // ── Driving / Powertrain ──
            // CONTINUOUS max 10Hz, FLOAT m/s, e.g. 0.0
            VhalPropertyIds.PERF_VEHICLE_SPEED to "PERF_VEHICLE_SPEED",
            // CONTINUOUS max 10Hz, FLOAT km, e.g. 23402.0
            VhalPropertyIds.PERF_ODOMETER to "PERF_ODOMETER",
            // CONTINUOUS max 10Hz, FLOAT degrees, e.g. 0.0
            VhalPropertyIds.PERF_STEERING_ANGLE to "PERF_STEERING_ANGLE",
            // CONTINUOUS max 10Hz, FLOAT rpm, e.g. 0.0
            VhalPropertyIds.ENGINE_RPM to "ENGINE_RPM",
            // ON_CHANGE, INT32 level enum, e.g. 2
            VhalPropertyIds.ENGINE_OIL_LEVEL to "ENGINE_OIL_LEVEL",
            // CONTINUOUS max 10Hz, FLOAT °C, e.g. 101.0
            VhalPropertyIds.ENGINE_OIL_TEMP to "ENGINE_OIL_TEMP",
            // ON_CHANGE, INT32 gear enum, e.g. 4 (DRIVE)
            VhalPropertyIds.GEAR_SELECTION to "GEAR_SELECTION",
            // ON_CHANGE, INT32, e.g. 4
            VhalPropertyIds.CURRENT_GEAR to "CURRENT_GEAR",
            // ON_CHANGE, INT32 state enum, e.g. 4 (START)
            VhalPropertyIds.IGNITION_STATE to "IGNITION_STATE",

            // ── Fuel / EV / Range ──
            // CONTINUOUS max 100Hz, FLOAT mL, e.g. 15000.0
            VhalPropertyIds.FUEL_LEVEL to "FUEL_LEVEL",
            // ON_CHANGE, BOOL, e.g. false
            VhalPropertyIds.FUEL_LEVEL_LOW to "FUEL_LEVEL_LOW",
            // CONTINUOUS max 100Hz, FLOAT Wh, e.g. 150000.0
            VhalPropertyIds.EV_BATTERY_LEVEL to "EV_BATTERY_LEVEL",
            // ON_CHANGE, INT32 charge state enum, e.g. 3
            VhalPropertyIds.EV_CHARGE_STATE to "EV_CHARGE_STATE",
            // ON_CHANGE, FLOAT Wh, e.g. 18950.0
            VhalPropertyIds.EV_CURRENT_BATTERY_CAPACITY to "EV_CURRENT_BATTERY_CAPACITY",
            // CONTINUOUS max 2Hz, FLOAT meters, e.g. 110000.0
            VhalPropertyIds.RANGE_REMAINING to "RANGE_REMAINING",

            // ── Body / Exterior / Lights ──
            // ON_CHANGE, BOOL, e.g. true
            VhalPropertyIds.PARKING_BRAKE_ON to "PARKING_BRAKE_ON",
            // ON_CHANGE, BOOL, DOOR area, e.g. true
            VhalPropertyIds.DOOR_LOCK to "DOOR_LOCK",
            // ON_CHANGE, INT32, WINDOW area, e.g. 0 (closed)
            VhalPropertyIds.WINDOW_POS to "WINDOW_POS",
            // ON_CHANGE, INT32 light state, e.g. 1 (ON)
            VhalPropertyIds.HEADLIGHTS_STATE to "HEADLIGHTS_STATE",
            // ON_CHANGE, INT32, e.g. 1
            VhalPropertyIds.HIGH_BEAM_LIGHTS_STATE to "HIGH_BEAM_LIGHTS_STATE",
            // ON_CHANGE, INT32, e.g. 1
            VhalPropertyIds.HAZARD_LIGHTS_STATE to "HAZARD_LIGHTS_STATE",
            // ON_CHANGE, INT32, e.g. 0 (OFF)
            VhalPropertyIds.REAR_FOG_LIGHTS_STATE to "REAR_FOG_LIGHTS_STATE",

            // ── Cabin / Comfort / Safety ──
            // CONTINUOUS max 2Hz, FLOAT WHEEL area kPa, e.g. 200.0
            VhalPropertyIds.TIRE_PRESSURE to "TIRE_PRESSURE",
            // CONTINUOUS max 2Hz, FLOAT °C, e.g. 23.0
            VhalPropertyIds.ENV_OUTSIDE_TEMPERATURE to "ENV_OUTSIDE_TEMPERATURE",
            // ON_CHANGE, INT32 GLOBAL 0-100, e.g. 100 (system-wide brightness)
            VhalPropertyIds.DISPLAY_BRIGHTNESS to "DISPLAY_BRIGHTNESS",
            // ON_CHANGE, INT32_VEC GLOBAL, per-display brightness levels, e.g. [0]
            VhalPropertyIds.PER_DISPLAY_BRIGHTNESS to "PER_DISPLAY_BRIGHTNESS",
            // ON_CHANGE, INT32 state, e.g. 0 (OFF)
            VhalPropertyIds.CABIN_LIGHTS_STATE to "CABIN_LIGHTS_STATE",
            // ON_CHANGE, BOOL SEAT area, e.g. false
            VhalPropertyIds.SEAT_BELT_BUCKLED to "SEAT_BELT_BUCKLED",
            // ON_CHANGE, INT32 SEAT area, e.g. 2 (OCCUPIED)
            VhalPropertyIds.SEAT_OCCUPANCY to "SEAT_OCCUPANCY",

            // ── ADAS / Driver Monitoring (ON_CHANGE) ──
            // INT32, e.g. 1 (ENABLED)
            VhalPropertyIds.AUTOMATIC_EMERGENCY_BRAKING_STATE
                to "AUTOMATIC_EMERGENCY_BRAKING_STATE",
            // INT32, e.g. 1 (ENABLED)
            VhalPropertyIds.FORWARD_COLLISION_WARNING_STATE
                to "FORWARD_COLLISION_WARNING_STATE",
            // INT32, e.g. 1 (ENABLED)
            VhalPropertyIds.LANE_DEPARTURE_WARNING_STATE
                to "LANE_DEPARTURE_WARNING_STATE",
            // INT32, e.g. 0 (DISABLED)
            VhalPropertyIds.LANE_KEEP_ASSIST_STATE to "LANE_KEEP_ASSIST_STATE",
            // INT32, e.g. 0
            VhalPropertyIds.EMERGENCY_LANE_KEEP_ASSIST_STATE
                to "EMERGENCY_LANE_KEEP_ASSIST_STATE",
            // INT32, e.g. 2 (driver hands state)
            VhalPropertyIds.HANDS_ON_DETECTION_DRIVER_STATE
                to "HANDS_ON_DETECTION_DRIVER_STATE",
            // INT32, e.g. 1 (warning level)
            VhalPropertyIds.HANDS_ON_DETECTION_WARNING
                to "HANDS_ON_DETECTION_WARNING",
            // INT32, e.g. 0
            VhalPropertyIds.DRIVER_DROWSINESS_ATTENTION_STATE
                to "DRIVER_DROWSINESS_ATTENTION_STATE",
            // INT32, e.g. 0
            VhalPropertyIds.DRIVER_DROWSINESS_ATTENTION_WARNING
                to "DRIVER_DROWSINESS_ATTENTION_WARNING",
            // INT32, e.g. 0
            VhalPropertyIds.DRIVER_DISTRACTION_STATE
                to "DRIVER_DISTRACTION_STATE",
            // INT32, e.g. 0
            VhalPropertyIds.DRIVER_DISTRACTION_WARNING
                to "DRIVER_DISTRACTION_WARNING",
            // INT32, e.g. 0
            VhalPropertyIds.CROSS_TRAFFIC_MONITORING_WARNING_STATE
                to "CROSS_TRAFFIC_MONITORING_WARNING_STATE",
            // INT32, e.g. 0
            VhalPropertyIds.LOW_SPEED_AUTOMATIC_EMERGENCY_BRAKING_STATE
                to "LOW_SPEED_AUTOMATIC_EMERGENCY_BRAKING_STATE",

            // ── System / Power / Time (ON_CHANGE unless noted) ──
            // INT32_VEC, power state request, e.g. [0,0]
            VhalPropertyIds.AP_POWER_STATE_REQ to "AP_POWER_STATE_REQ",
            // INT32_VEC [RW], power state report, e.g. [1,0]
            VhalPropertyIds.AP_POWER_STATE_REPORT to "AP_POWER_STATE_REPORT",
            // INT64, epoch ms, e.g. 1778479041000
            VhalPropertyIds.EXTERNAL_CAR_TIME to "EXTERNAL_CAR_TIME",
            // INT32, e.g. 145 (KM_PER_HOUR)
            VhalPropertyIds.VEHICLE_SPEED_DISPLAY_UNITS
                to "VEHICLE_SPEED_DISPLAY_UNITS",
            // INT32, e.g. 35
            VhalPropertyIds.DISTANCE_DISPLAY_UNITS to "DISTANCE_DISPLAY_UNITS",
            // INT32 STATIC, e.g. 100000 (grams)
            VhalPropertyIds.VEHICLE_CURB_WEIGHT to "VEHICLE_CURB_WEIGHT",

            // ── HVAC / Climate (real HW only, gracefully skipped on emulator) ──
            // ON_CHANGE, INT32 SEAT area, fan level
            VhalPropertyIds.HVAC_FAN_SPEED to "HVAC_FAN_SPEED",
            // ON_CHANGE, INT32 SEAT area
            VhalPropertyIds.HVAC_FAN_DIRECTION to "HVAC_FAN_DIRECTION",
            // ON_CHANGE, INT32 SEAT area
            VhalPropertyIds.HVAC_FAN_DIRECTION_AVAILABLE
                to "HVAC_FAN_DIRECTION_AVAILABLE",
            // ON_CHANGE, INT32 SEAT area, rpm
            VhalPropertyIds.HVAC_ACTUAL_FAN_SPEED_RPM
                to "HVAC_ACTUAL_FAN_SPEED_RPM",
            // ON_CHANGE, FLOAT SEAT area, °C
            VhalPropertyIds.HVAC_TEMPERATURE_SET to "HVAC_TEMPERATURE_SET",
            // ON_CHANGE, FLOAT SEAT area, °C
            VhalPropertyIds.HVAC_TEMPERATURE_CURRENT
                to "HVAC_TEMPERATURE_CURRENT",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_AC_ON to "HVAC_AC_ON",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_MAX_AC_ON to "HVAC_MAX_AC_ON",
            // ON_CHANGE, BOOL WINDOW area
            VhalPropertyIds.HVAC_DEFROSTER to "HVAC_DEFROSTER",
            // ON_CHANGE, BOOL WINDOW area
            VhalPropertyIds.HVAC_ELECTRIC_DEFROSTER_ON
                to "HVAC_ELECTRIC_DEFROSTER_ON",
            // ON_CHANGE, BOOL GLOBAL
            VhalPropertyIds.HVAC_MAX_DEFROST_ON to "HVAC_MAX_DEFROST_ON",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_RECIRC_ON to "HVAC_RECIRC_ON",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_AUTO_RECIRC_ON to "HVAC_AUTO_RECIRC_ON",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_DUAL_ON to "HVAC_DUAL_ON",
            // ON_CHANGE, BOOL SEAT area — emulator ✓
            VhalPropertyIds.HVAC_AUTO_ON to "HVAC_AUTO_ON",
            // ON_CHANGE, BOOL SEAT area
            VhalPropertyIds.HVAC_POWER_ON to "HVAC_POWER_ON",
            // ON_CHANGE, INT32 SEAT area, level
            VhalPropertyIds.HVAC_SEAT_TEMPERATURE to "HVAC_SEAT_TEMPERATURE",
            // ON_CHANGE, INT32 SEAT area, level
            VhalPropertyIds.HVAC_SEAT_VENTILATION to "HVAC_SEAT_VENTILATION",
            // ON_CHANGE, INT32 GLOBAL, level
            VhalPropertyIds.HVAC_STEERING_WHEEL_HEAT
                to "HVAC_STEERING_WHEEL_HEAT",
            // ON_CHANGE, BOOL GLOBAL
            VhalPropertyIds.HVAC_SIDE_MIRROR_HEAT to "HVAC_SIDE_MIRROR_HEAT",
            // ON_CHANGE, INT32, e.g. 48 (CELSIUS) — emulator ✓
            VhalPropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS
                to "HVAC_TEMPERATURE_DISPLAY_UNITS",
            // ON_CHANGE, FLOAT_VEC — emulator ✓
            VhalPropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION
                to "HVAC_TEMPERATURE_VALUE_SUGGESTION",

            // ── Porsche Vendor (ON_CHANGE, gracefully skipped if unavailable) ──
            // INT32, power clamps state, e.g. 1
            VhalPropertyIds.PORSCHE_CLAMPS_STATE to "PORSCHE_CLAMPS_STATE",
            // BOOL, system shutdown requested
            VhalPropertyIds.PORSCHE_SHUTDOWN_FLAG to "PORSCHE_SHUTDOWN_FLAG",
            // BOOL, cluster dark/night mode, e.g. true
            VhalPropertyIds.PORSCHE_CLUSTER_NIGHT_MODE
                to "PORSCHE_CLUSTER_NIGHT_MODE",
            // INT32, wakeup reason enum, e.g. 1
            VhalPropertyIds.PORSCHE_WAKE_UP_REASON to "PORSCHE_WAKE_UP_REASON",
            // INT32, battery/energy warning, e.g. 0
            VhalPropertyIds.PORSCHE_BEM_WARNING to "PORSCHE_BEM_WARNING",
            // STRING, mobile network provider name
            VhalPropertyIds.PORSCHE_NETWORK_PROVIDER
                to "PORSCHE_NETWORK_PROVIDER",
            // STRING, connection type, e.g. "4.5G"
            VhalPropertyIds.PORSCHE_NETWORK_TYPE to "PORSCHE_NETWORK_TYPE",
            // INT32, signal quality 0-5, e.g. 3
            VhalPropertyIds.PORSCHE_NETWORK_SIGNAL_QUALITY
                to "PORSCHE_NETWORK_SIGNAL_QUALITY",
            // FLOAT, EV range in km, e.g. 189.5
            VhalPropertyIds.PORSCHE_EV_ENERGY_RANGE
                to "PORSCHE_EV_ENERGY_RANGE",
            // FLOAT, primary range in km, e.g. 110.0
            VhalPropertyIds.PORSCHE_EV_CURRENT_RANGES_PRIMARY
                to "PORSCHE_EV_CURRENT_RANGES_PRIMARY",

            // ── Porsche Diagnostics (STATIC / ON_CHANGE) ──
            // INT32 STATIC, car class enum, e.g. 5
            VhalPropertyIds.PORSCHE_DIAG_CAR_CLASS to "PORSCHE_DIAG_CAR_CLASS",
            // INT32 STATIC, body style enum, e.g. 3
            VhalPropertyIds.PORSCHE_DIAG_CAR_BODYSTYLE
                to "PORSCHE_DIAG_CAR_BODYSTYLE",
            // STRING, full variant code, e.g. "FM3-P-S-101-NDCWB6-EU-PO-ML2-DE"
            VhalPropertyIds.PORSCHE_DIAG_VARIANT_STRING
                to "PORSCHE_DIAG_VARIANT_STRING",
            // STRING, SW version, e.g. "X047"
            VhalPropertyIds.PORSCHE_DIAG_SOFTWARE_VERSION
                to "PORSCHE_DIAG_SOFTWARE_VERSION",
            // STRING [RW], HMI country code, e.g. "DE"
            VhalPropertyIds.PORSCHE_DIAG_COUNTRY_CODE_HMI
                to "PORSCHE_DIAG_COUNTRY_CODE_HMI",
            // INT32 [RW], MMTR availability flag, e.g. 1
            VhalPropertyIds.PORSCHE_DIAG_MMTR_AVAILABLE
                to "PORSCHE_DIAG_MMTR_AVAILABLE",

            // ── Porsche Telephony (ON_CHANGE) ──
            // INT32, call state enum
            VhalPropertyIds.PORSCHE_CALL_STATE to "PORSCHE_CALL_STATE",
            // INT32 [RW], Android call state
            VhalPropertyIds.PORSCHE_ANDROID_CALL_STATE
                to "PORSCHE_ANDROID_CALL_STATE",
            // INT32_VEC [RW], telephony subsystem states
            VhalPropertyIds.INT_TELEPHONY_STATE to "INT_TELEPHONY_STATE",
        )
    }
}
