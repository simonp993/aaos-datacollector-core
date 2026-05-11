package com.porsche.aaos.platform.telemetry.vehicleplatform

import android.car.VehiclePropertyIds
import vendor.porsche.hardware.vehiclevendorextension.VehicleProperty

@Suppress("TooManyFunctions")
object VhalPropertyIds {
    // Porsche vendor extensions
    val PORSCHE_DIAG_MMTR_AVAILABLE: Int = VehicleProperty.PORSCHE_DIAG_MMTR_AVAILABLE
    val PORSCHE_AP_OPERATING_MODE: Int = VehicleProperty.PORSCHE_AP_OPERATING_MODE
    val PORSCHE_CLAMPS_STATE: Int = VehicleProperty.PORSCHE_CLAMPS_STATE
    val PORSCHE_SHUTDOWN_FLAG: Int = VehicleProperty.PORSCHE_SHUTDOWN_FLAG
    val PORSCHE_CLUSTER_NIGHT_MODE: Int = VehicleProperty.PORSCHE_CLUSTER_NIGHT_MODE
    val PORSCHE_NETWORK_PROVIDER: Int = VehicleProperty.PORSCHE_NETWORK_PROVIDER
    val PORSCHE_NETWORK_TYPE: Int = VehicleProperty.PORSCHE_NETWORK_TYPE
    val PORSCHE_NETWORK_SIGNAL_QUALITY: Int = VehicleProperty.PORSCHE_NETWORK_SIGNAL_QUALITY
    val PORSCHE_BEM_WARNING: Int = VehicleProperty.PORSCHE_BEM_WARNING
    val PORSCHE_WAKE_UP_REASON: Int = VehicleProperty.PORSCHE_WAKE_UP_REASON
    val PORSCHE_EV_ENERGY_RANGE: Int = VehicleProperty.PORSCHE_EV_ENERGY_RANGE
    val PORSCHE_EV_CURRENT_RANGES_PRIMARY: Int =
        VehicleProperty.PORSCHE_EV_CURRENT_RANGES_PRIMARY
    val PORSCHE_DIAG_CAR_CLASS: Int = VehicleProperty.PORSCHE_DIAG_CAR_CLASS
    val PORSCHE_DIAG_CAR_BODYSTYLE: Int = VehicleProperty.PORSCHE_DIAG_CAR_BODYSTYLE
    val PORSCHE_DIAG_VARIANT_STRING: Int = VehicleProperty.PORSCHE_DIAG_VARIANT_STRING
    val PORSCHE_DIAG_SOFTWARE_VERSION: Int = VehicleProperty.PORSCHE_DIAG_SOFTWARE_VERSION
    val PORSCHE_DIAG_COUNTRY_CODE_HMI: Int = VehicleProperty.PORSCHE_DIAG_COUNTRY_CODE_HMI
    val PORSCHE_CALL_STATE: Int = VehicleProperty.PORSCHE_CALL_STATE
    val PORSCHE_ANDROID_CALL_STATE: Int = VehicleProperty.PORSCHE_ANDROID_CALL_STATE
    val INT_TELEPHONY_STATE: Int = VehicleProperty.INT_TELEPHONY_STATE

    // Standard AAOS property IDs (forwarded from android.car.VehiclePropertyIds)
    val PERF_VEHICLE_SPEED: Int = VehiclePropertyIds.PERF_VEHICLE_SPEED
    val PERF_VEHICLE_SPEED_DISPLAY: Int = VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY
    val PERF_ODOMETER: Int = VehiclePropertyIds.PERF_ODOMETER
    val PERF_STEERING_ANGLE: Int = VehiclePropertyIds.PERF_STEERING_ANGLE
    val ENGINE_RPM: Int = VehiclePropertyIds.ENGINE_RPM
    val ENGINE_OIL_LEVEL: Int = VehiclePropertyIds.ENGINE_OIL_LEVEL
    val ENGINE_OIL_TEMP: Int = VehiclePropertyIds.ENGINE_OIL_TEMP
    val GEAR_SELECTION: Int = VehiclePropertyIds.GEAR_SELECTION
    val CURRENT_GEAR: Int = VehiclePropertyIds.CURRENT_GEAR
    val FUEL_LEVEL: Int = VehiclePropertyIds.FUEL_LEVEL
    val FUEL_LEVEL_LOW: Int = VehiclePropertyIds.FUEL_LEVEL_LOW
    val EV_BATTERY_LEVEL: Int = VehiclePropertyIds.EV_BATTERY_LEVEL
    val EV_CHARGE_STATE: Int = VehiclePropertyIds.EV_CHARGE_STATE
    val EV_CURRENT_BATTERY_CAPACITY: Int = VehiclePropertyIds.EV_CURRENT_BATTERY_CAPACITY
    val RANGE_REMAINING: Int = VehiclePropertyIds.RANGE_REMAINING
    val IGNITION_STATE: Int = VehiclePropertyIds.IGNITION_STATE
    val PARKING_BRAKE_ON: Int = VehiclePropertyIds.PARKING_BRAKE_ON
    val NIGHT_MODE: Int = VehiclePropertyIds.NIGHT_MODE
    val VEHICLE_IN_USE: Int = VehiclePropertyIds.VEHICLE_IN_USE
    val DOOR_LOCK: Int = VehiclePropertyIds.DOOR_LOCK
    val HEADLIGHTS_STATE: Int = VehiclePropertyIds.HEADLIGHTS_STATE
    val HIGH_BEAM_LIGHTS_STATE: Int = VehiclePropertyIds.HIGH_BEAM_LIGHTS_STATE
    val HAZARD_LIGHTS_STATE: Int = VehiclePropertyIds.HAZARD_LIGHTS_STATE
    val REAR_FOG_LIGHTS_STATE: Int = VehiclePropertyIds.REAR_FOG_LIGHTS_STATE
    val TURN_SIGNAL_STATE: Int = VehiclePropertyIds.TURN_SIGNAL_STATE
    val ELECTRONIC_STABILITY_CONTROL_STATE: Int =
        VehiclePropertyIds.ELECTRONIC_STABILITY_CONTROL_STATE
    val ENV_OUTSIDE_TEMPERATURE: Int = VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE
    val DISPLAY_BRIGHTNESS: Int = VehiclePropertyIds.DISPLAY_BRIGHTNESS
    val CABIN_LIGHTS_STATE: Int = VehiclePropertyIds.CABIN_LIGHTS_STATE
    val ABS_ACTIVE: Int = VehiclePropertyIds.ABS_ACTIVE
    val TRACTION_CONTROL_ACTIVE: Int = VehiclePropertyIds.TRACTION_CONTROL_ACTIVE
    val TIRE_PRESSURE: Int = VehiclePropertyIds.TIRE_PRESSURE
    val SEAT_BELT_BUCKLED: Int = VehiclePropertyIds.SEAT_BELT_BUCKLED
    val SEAT_OCCUPANCY: Int = VehiclePropertyIds.SEAT_OCCUPANCY
    val WHEEL_TICK: Int = VehiclePropertyIds.WHEEL_TICK
    val WINDOW_POS: Int = VehiclePropertyIds.WINDOW_POS
    val PER_DISPLAY_BRIGHTNESS: Int = VehiclePropertyIds.PER_DISPLAY_BRIGHTNESS

    // ADAS / Driver Monitoring
    val AUTOMATIC_EMERGENCY_BRAKING_STATE: Int =
        VehiclePropertyIds.AUTOMATIC_EMERGENCY_BRAKING_STATE
    val FORWARD_COLLISION_WARNING_STATE: Int =
        VehiclePropertyIds.FORWARD_COLLISION_WARNING_STATE
    val LANE_DEPARTURE_WARNING_STATE: Int =
        VehiclePropertyIds.LANE_DEPARTURE_WARNING_STATE
    val LANE_KEEP_ASSIST_STATE: Int = VehiclePropertyIds.LANE_KEEP_ASSIST_STATE
    val EMERGENCY_LANE_KEEP_ASSIST_STATE: Int =
        VehiclePropertyIds.EMERGENCY_LANE_KEEP_ASSIST_STATE
    val HANDS_ON_DETECTION_DRIVER_STATE: Int =
        VehiclePropertyIds.HANDS_ON_DETECTION_DRIVER_STATE
    val HANDS_ON_DETECTION_WARNING: Int =
        VehiclePropertyIds.HANDS_ON_DETECTION_WARNING
    val DRIVER_DROWSINESS_ATTENTION_STATE: Int =
        VehiclePropertyIds.DRIVER_DROWSINESS_ATTENTION_STATE
    val DRIVER_DROWSINESS_ATTENTION_WARNING: Int =
        VehiclePropertyIds.DRIVER_DROWSINESS_ATTENTION_WARNING
    val DRIVER_DISTRACTION_STATE: Int = VehiclePropertyIds.DRIVER_DISTRACTION_STATE
    val DRIVER_DISTRACTION_WARNING: Int = VehiclePropertyIds.DRIVER_DISTRACTION_WARNING
    val CROSS_TRAFFIC_MONITORING_WARNING_STATE: Int =
        VehiclePropertyIds.CROSS_TRAFFIC_MONITORING_WARNING_STATE
    val LOW_SPEED_AUTOMATIC_EMERGENCY_BRAKING_STATE: Int =
        VehiclePropertyIds.LOW_SPEED_AUTOMATIC_EMERGENCY_BRAKING_STATE

    // System / Power / Time
    val AP_POWER_STATE_REQ: Int = VehiclePropertyIds.AP_POWER_STATE_REQ
    val AP_POWER_STATE_REPORT: Int = VehiclePropertyIds.AP_POWER_STATE_REPORT
    val EXTERNAL_CAR_TIME: Int = VehiclePropertyIds.EXTERNAL_CAR_TIME
    val VEHICLE_SPEED_DISPLAY_UNITS: Int = VehiclePropertyIds.VEHICLE_SPEED_DISPLAY_UNITS
    val DISTANCE_DISPLAY_UNITS: Int = VehiclePropertyIds.DISTANCE_DISPLAY_UNITS
    val VEHICLE_CURB_WEIGHT: Int = VehiclePropertyIds.VEHICLE_CURB_WEIGHT

    // HVAC / Climate
    val HVAC_FAN_SPEED: Int = VehiclePropertyIds.HVAC_FAN_SPEED
    val HVAC_FAN_DIRECTION: Int = VehiclePropertyIds.HVAC_FAN_DIRECTION
    val HVAC_FAN_DIRECTION_AVAILABLE: Int = VehiclePropertyIds.HVAC_FAN_DIRECTION_AVAILABLE
    val HVAC_ACTUAL_FAN_SPEED_RPM: Int = VehiclePropertyIds.HVAC_ACTUAL_FAN_SPEED_RPM
    val HVAC_TEMPERATURE_SET: Int = VehiclePropertyIds.HVAC_TEMPERATURE_SET
    val HVAC_TEMPERATURE_CURRENT: Int = VehiclePropertyIds.HVAC_TEMPERATURE_CURRENT
    val HVAC_AC_ON: Int = VehiclePropertyIds.HVAC_AC_ON
    val HVAC_MAX_AC_ON: Int = VehiclePropertyIds.HVAC_MAX_AC_ON
    val HVAC_DEFROSTER: Int = VehiclePropertyIds.HVAC_DEFROSTER
    val HVAC_ELECTRIC_DEFROSTER_ON: Int = VehiclePropertyIds.HVAC_ELECTRIC_DEFROSTER_ON
    val HVAC_MAX_DEFROST_ON: Int = VehiclePropertyIds.HVAC_MAX_DEFROST_ON
    val HVAC_RECIRC_ON: Int = VehiclePropertyIds.HVAC_RECIRC_ON
    val HVAC_AUTO_RECIRC_ON: Int = VehiclePropertyIds.HVAC_AUTO_RECIRC_ON
    val HVAC_DUAL_ON: Int = VehiclePropertyIds.HVAC_DUAL_ON
    val HVAC_AUTO_ON: Int = VehiclePropertyIds.HVAC_AUTO_ON
    val HVAC_POWER_ON: Int = VehiclePropertyIds.HVAC_POWER_ON
    val HVAC_SEAT_TEMPERATURE: Int = VehiclePropertyIds.HVAC_SEAT_TEMPERATURE
    val HVAC_SEAT_VENTILATION: Int = VehiclePropertyIds.HVAC_SEAT_VENTILATION
    val HVAC_STEERING_WHEEL_HEAT: Int = VehiclePropertyIds.HVAC_STEERING_WHEEL_HEAT
    val HVAC_SIDE_MIRROR_HEAT: Int = VehiclePropertyIds.HVAC_SIDE_MIRROR_HEAT
    val HVAC_TEMPERATURE_DISPLAY_UNITS: Int = VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS
    val HVAC_TEMPERATURE_VALUE_SUGGESTION: Int = VehiclePropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION

    // Vehicle Info (static / read-once)
    val INFO_VIN: Int = VehiclePropertyIds.INFO_VIN
    val INFO_MAKE: Int = VehiclePropertyIds.INFO_MAKE
    val INFO_MODEL: Int = VehiclePropertyIds.INFO_MODEL
    val INFO_MODEL_YEAR: Int = VehiclePropertyIds.INFO_MODEL_YEAR
    val INFO_FUEL_TYPE: Int = VehiclePropertyIds.INFO_FUEL_TYPE
    val INFO_EV_CONNECTOR_TYPE: Int = VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE
}
