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

    // For SAMPLED properties: stores the latest value received from VHAL.
    // A periodic job reads these at the configured interval and accumulates
    // them into a shared sample buffer. The buffer is flushed every BATCH_FLUSH_MS.
    private val sampledLatest = ConcurrentHashMap<Int, Any>()
    private val sampledBuffer = mutableListOf<List<Any>>()
    private val sampledJobs = mutableListOf<Job>()

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting VHAL observation (immediate per-property emission)")

        // Start sampling jobs: each reads latest values at its interval into buffers
        val sampledByInterval = OBSERVED_PROPERTIES
            .filter { it.mode is SampleMode.Sampled }
            .groupBy { (it.mode as SampleMode.Sampled).intervalMs }

        val propLookup = OBSERVED_PROPERTIES.associateBy { it.propertyId }

        // Seed sampledLatest with an initial read so properties that don't change
        // (e.g., speed=0 while stationary) still appear in sampled batches.
        for (prop in OBSERVED_PROPERTIES.filter { it.mode is SampleMode.Sampled }) {
            try {
                vhalPropertyService.readProperty<Any>(prop.propertyId)?.let {
                    sampledLatest[prop.propertyId] = it
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.w(TAG, "Initial read failed for ${prop.name}: ${e.message}")
            }
        }

        for ((intervalMs, props) in sampledByInterval) {
            val propIds = props.map { it.propertyId }.toSet()
            val job = CoroutineScope(Dispatchers.Default).launch {
                delay(STAGGER_DELAY_MS)
                while (isActive) {
                    delay(intervalMs)
                    val ts = System.currentTimeMillis()
                    for (propertyId in propIds) {
                        val value = sampledLatest[propertyId] ?: continue
                        val name = propLookup[propertyId]?.name ?: continue
                        synchronized(sampledBuffer) {
                            sampledBuffer.add(listOf(ts, name, value))
                        }
                    }
                }
            }
            sampledJobs.add(job)
            logger.i(
                TAG,
                "Sampling ${props.size} properties every ${intervalMs / 1000}s: " +
                    props.joinToString { it.name },
            )
        }

        // Flush job: emits single batched event every BATCH_FLUSH_MS
        val flushJob = CoroutineScope(Dispatchers.Default).launch {
            delay(STAGGER_DELAY_MS + BATCH_FLUSH_MS)
            while (isActive) {
                flushSampledBatch()
                delay(BATCH_FLUSH_MS)
            }
        }
        sampledJobs.add(flushJob)

        coroutineScope {
            OBSERVED_PROPERTIES.forEach { prop ->
                launch {
                    vhalPropertyService.observeProperty<Any>(prop.propertyId)
                        .catch { e ->
                            logger.w(TAG, "Failed to observe ${prop.name} (${prop.propertyId})", e)
                        }
                        .collect { value ->
                            if (!running) return@collect

                            when (prop.mode) {
                                is SampleMode.OnChange -> {
                                    val previous = previousValues.put(prop.propertyId, value)
                                    if (value == previous) return@collect
                                    emitChange(prop.name, previous, value)
                                }
                                is SampleMode.Sampled -> {
                                    sampledLatest[prop.propertyId] = value
                                }
                            }
                        }
                }
            }
        }
    }

    override fun stop() {
        running = false
        sampledJobs.forEach { it.cancel() }
        sampledJobs.clear()
        sampledLatest.clear()
        sampledBuffer.clear()
        previousValues.clear()
        logger.i(TAG, "Stopped")
    }

    private fun flushSampledBatch() {
        val samples: List<List<Any>>
        synchronized(sampledBuffer) {
            if (sampledBuffer.isEmpty()) return
            samples = sampledBuffer.toList()
            sampledBuffer.clear()
        }
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "VHAL_SampledBatch",
                    "trigger" to "vehicle",
                    "metadata" to mapOf(
                        "sampleSchema" to listOf("ts", "property", "value"),
                        "samples" to samples,
                    ),
                ),
            ),
        )
    }

    private fun emitChange(property: String, previous: Any?, current: Any?) {
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to "VHAL_ValueChanged",
                    "trigger" to "vehicle",
                    "metadata" to mapOf(
                        "property" to property,
                        "previous" to previous,
                        "current" to current,
                    ),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "VehiclePropertyCollector"
        private const val STAGGER_DELAY_MS = 6_000L
        private const val BATCH_FLUSH_MS = 60_000L
        private const val SAMPLE_5S = 5_000L
        private const val SAMPLE_10S = 10_000L
        private const val SAMPLE_30S = 30_000L

        private sealed interface SampleMode {
            data object OnChange : SampleMode
            data class Sampled(val intervalMs: Long) : SampleMode
        }

        private data class ObservedProperty(
            val propertyId: Int,
            val name: String,
            val mode: SampleMode = SampleMode.OnChange,
        )

        // Verified against MIB4 Cayenne (2026-05-12) via:
        //   adb shell cmd car_service get-carpropertyconfig
        // Properties not registered in VHAL have been removed entirely.
        // Properties marked "access denied" are registered but require
        // platform-key signing — will work once the APK is signed correctly.
        private val OBSERVED_PROPERTIES = listOf(
            // ── Driving / Powertrain ──
            ObservedProperty(VhalPropertyIds.PERF_VEHICLE_SPEED, "PERF_VEHICLE_SPEED", SampleMode.Sampled(SAMPLE_5S)),
            // access denied — needs CAR_MILEAGE (signature-level)
            // ObservedProperty(VhalPropertyIds.PERF_ODOMETER, "PERF_ODOMETER", SampleMode.Sampled(SAMPLE_30S)),
            // access denied — needs CAR_ENGINE_DETAILED (signature-level)
            // ObservedProperty(VhalPropertyIds.PERF_STEERING_ANGLE, "PERF_STEERING_ANGLE", SampleMode.Sampled(SAMPLE_5S)),
            // ObservedProperty(VhalPropertyIds.ENGINE_RPM, "ENGINE_RPM", SampleMode.Sampled(SAMPLE_5S)),
            // ObservedProperty(VhalPropertyIds.ENGINE_OIL_LEVEL, "ENGINE_OIL_LEVEL"),
            // ObservedProperty(VhalPropertyIds.ENGINE_OIL_TEMP, "ENGINE_OIL_TEMP", SampleMode.Sampled(SAMPLE_30S)),
            ObservedProperty(VhalPropertyIds.GEAR_SELECTION, "GEAR_SELECTION"), // redundant with CURRENT_GEAR but included for completeness
            ObservedProperty(VhalPropertyIds.CURRENT_GEAR, "CURRENT_GEAR"), // 8 = D, 1 = N, 2 = R, 4 = P
            ObservedProperty(VhalPropertyIds.IGNITION_STATE, "IGNITION_STATE"), // 4 = On, 3 = Off

            // ── Fuel / EV / Range ──
            ObservedProperty(VhalPropertyIds.FUEL_LEVEL, "FUEL_LEVEL", SampleMode.Sampled(SAMPLE_30S)),
            ObservedProperty(VhalPropertyIds.FUEL_LEVEL_LOW, "FUEL_LEVEL_LOW"),
            ObservedProperty(VhalPropertyIds.EV_BATTERY_LEVEL, "EV_BATTERY_LEVEL", SampleMode.Sampled(SAMPLE_30S)),
            ObservedProperty(VhalPropertyIds.RANGE_REMAINING, "RANGE_REMAINING", SampleMode.Sampled(SAMPLE_30S)),

            // ── Body / Exterior / Lights ──
            ObservedProperty(VhalPropertyIds.PARKING_BRAKE_ON, "PARKING_BRAKE_ON"),
            // access denied — needs CONTROL_CAR_DOORS (signature-level)
            // ObservedProperty(VhalPropertyIds.DOOR_LOCK, "DOOR_LOCK"),
            // access denied — needs CONTROL_CAR_WINDOWS (signature-level)
            // ObservedProperty(VhalPropertyIds.WINDOW_POS, "WINDOW_POS"),
            ObservedProperty(VhalPropertyIds.HEADLIGHTS_STATE, "HEADLIGHTS_STATE"),

            // ── Cabin / Comfort / Safety ──
            ObservedProperty(VhalPropertyIds.ENV_OUTSIDE_TEMPERATURE, "ENV_OUTSIDE_TEMPERATURE", SampleMode.Sampled(SAMPLE_30S)),
            // access denied — needs CAR_INTERIOR_LIGHTING (signature-level)
            // ObservedProperty(VhalPropertyIds.CABIN_LIGHTS_STATE, "CABIN_LIGHTS_STATE"),
            // access denied — needs READ_CAR_OCCUPANT_AWARENESS_STATE (signature-level)
            // ObservedProperty(VhalPropertyIds.SEAT_OCCUPANCY, "SEAT_OCCUPANCY"),

            // ── System / Power / Time ──
            // access denied — needs CAR_POWER (signature-level)
            // ObservedProperty(VhalPropertyIds.AP_POWER_STATE_REQ, "AP_POWER_STATE_REQ"),
            // access denied — needs READ_CAR_DISPLAY_UNITS (signature-level)
            // ObservedProperty(VhalPropertyIds.VEHICLE_SPEED_DISPLAY_UNITS, "VEHICLE_SPEED_DISPLAY_UNITS"),

            // ── HVAC / Climate ──
            // access denied — needs CONTROL_CAR_CLIMATE (signature-level)
            // ObservedProperty(VhalPropertyIds.HVAC_AUTO_RECIRC_ON, "HVAC_AUTO_RECIRC_ON"),
            // ObservedProperty(VhalPropertyIds.HVAC_STEERING_WHEEL_HEAT, "HVAC_STEERING_WHEEL_HEAT"),
            ObservedProperty(VhalPropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, "HVAC_TEMPERATURE_DISPLAY_UNITS"),

            // ── Porsche Vendor ──
            ObservedProperty(VhalPropertyIds.PORSCHE_CLAMPS_STATE, "PORSCHE_CLAMPS_STATE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_SHUTDOWN_FLAG, "PORSCHE_SHUTDOWN_FLAG"),
            ObservedProperty(VhalPropertyIds.PORSCHE_CLUSTER_NIGHT_MODE, "PORSCHE_CLUSTER_NIGHT_MODE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_WAKE_UP_REASON, "PORSCHE_WAKE_UP_REASON"),
            ObservedProperty(VhalPropertyIds.PORSCHE_BEM_WARNING, "PORSCHE_BEM_WARNING"),
            ObservedProperty(VhalPropertyIds.PORSCHE_NETWORK_PROVIDER, "PORSCHE_NETWORK_PROVIDER"),
            ObservedProperty(VhalPropertyIds.PORSCHE_NETWORK_TYPE, "PORSCHE_NETWORK_TYPE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_NETWORK_SIGNAL_QUALITY, "PORSCHE_NETWORK_SIGNAL_QUALITY"),
            ObservedProperty(VhalPropertyIds.PORSCHE_EV_ENERGY_RANGE, "PORSCHE_EV_ENERGY_RANGE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_EV_CURRENT_RANGES_PRIMARY, "PORSCHE_EV_CURRENT_RANGES_PRIMARY"),

            // ── Porsche Diagnostics ──
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_CAR_CLASS, "PORSCHE_DIAG_CAR_CLASS"),
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_CAR_BODYSTYLE, "PORSCHE_DIAG_CAR_BODYSTYLE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_VARIANT_STRING, "PORSCHE_DIAG_VARIANT_STRING"),
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_SOFTWARE_VERSION, "PORSCHE_DIAG_SOFTWARE_VERSION"),
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_COUNTRY_CODE_HMI, "PORSCHE_DIAG_COUNTRY_CODE_HMI"),
            ObservedProperty(VhalPropertyIds.PORSCHE_DIAG_MMTR_AVAILABLE, "PORSCHE_DIAG_MMTR_AVAILABLE"),

            // ── Porsche Telephony ──
            ObservedProperty(VhalPropertyIds.PORSCHE_CALL_STATE, "PORSCHE_CALL_STATE"),
            ObservedProperty(VhalPropertyIds.PORSCHE_ANDROID_CALL_STATE, "PORSCHE_ANDROID_CALL_STATE"),
            ObservedProperty(VhalPropertyIds.INT_TELEPHONY_STATE, "INT_TELEPHONY_STATE"),
        )
    }
}
