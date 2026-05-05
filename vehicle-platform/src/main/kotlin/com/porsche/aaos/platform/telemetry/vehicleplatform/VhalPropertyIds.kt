package com.porsche.aaos.platform.telemetry.vehicleplatform

import android.car.VehiclePropertyIds
import vendor.porsche.hardware.vehiclevendorextension.VehicleProperty

@Suppress("TooManyFunctions")
object VhalPropertyIds {
    // Porsche vendor extensions
    val PORSCHE_DIAG_MMTR_AVAILABLE: Int = VehicleProperty.PORSCHE_DIAG_MMTR_AVAILABLE

    // Standard AAOS property IDs (forwarded from android.car.VehiclePropertyIds)
    val PERF_VEHICLE_SPEED: Int = VehiclePropertyIds.PERF_VEHICLE_SPEED
    val ENGINE_RPM: Int = VehiclePropertyIds.ENGINE_RPM
    val GEAR_SELECTION: Int = VehiclePropertyIds.GEAR_SELECTION
    val CURRENT_GEAR: Int = VehiclePropertyIds.CURRENT_GEAR
    val FUEL_LEVEL: Int = VehiclePropertyIds.FUEL_LEVEL
    val FUEL_LEVEL_LOW: Int = VehiclePropertyIds.FUEL_LEVEL_LOW
    val EV_BATTERY_LEVEL: Int = VehiclePropertyIds.EV_BATTERY_LEVEL
    val IGNITION_STATE: Int = VehiclePropertyIds.IGNITION_STATE
    val PARKING_BRAKE_ON: Int = VehiclePropertyIds.PARKING_BRAKE_ON
    val NIGHT_MODE: Int = VehiclePropertyIds.NIGHT_MODE
    val DOOR_LOCK: Int = VehiclePropertyIds.DOOR_LOCK
    val HEADLIGHTS_STATE: Int = VehiclePropertyIds.HEADLIGHTS_STATE
    val TURN_SIGNAL_STATE: Int = VehiclePropertyIds.TURN_SIGNAL_STATE
    val ENV_OUTSIDE_TEMPERATURE: Int = VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE
    val DISPLAY_BRIGHTNESS: Int = VehiclePropertyIds.DISPLAY_BRIGHTNESS
    val CABIN_LIGHTS_STATE: Int = VehiclePropertyIds.CABIN_LIGHTS_STATE
    val ABS_ACTIVE: Int = VehiclePropertyIds.ABS_ACTIVE
    val TRACTION_CONTROL_ACTIVE: Int = VehiclePropertyIds.TRACTION_CONTROL_ACTIVE
    val TIRE_PRESSURE: Int = VehiclePropertyIds.TIRE_PRESSURE
    val SEAT_BELT_BUCKLED: Int = VehiclePropertyIds.SEAT_BELT_BUCKLED
    val SEAT_OCCUPANCY: Int = VehiclePropertyIds.SEAT_OCCUPANCY

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
