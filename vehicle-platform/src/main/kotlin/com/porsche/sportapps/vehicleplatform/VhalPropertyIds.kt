package com.porsche.sportapps.vehicleplatform

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
    val FUEL_LEVEL: Int = VehiclePropertyIds.FUEL_LEVEL
    val EV_BATTERY_LEVEL: Int = VehiclePropertyIds.EV_BATTERY_LEVEL
    val HVAC_TEMPERATURE_SET: Int = VehiclePropertyIds.HVAC_TEMPERATURE_SET
    val DOOR_LOCK: Int = VehiclePropertyIds.DOOR_LOCK
    val HEADLIGHTS_STATE: Int = VehiclePropertyIds.HEADLIGHTS_STATE
    val TURN_SIGNAL_STATE: Int = VehiclePropertyIds.TURN_SIGNAL_STATE
    val PARKING_BRAKE_ON: Int = VehiclePropertyIds.PARKING_BRAKE_ON
    val INFO_VIN: Int = VehiclePropertyIds.INFO_VIN
    val INFO_MAKE: Int = VehiclePropertyIds.INFO_MAKE
    val INFO_MODEL: Int = VehiclePropertyIds.INFO_MODEL
    val INFO_MODEL_YEAR: Int = VehiclePropertyIds.INFO_MODEL_YEAR
    val INFO_FUEL_TYPE: Int = VehiclePropertyIds.INFO_FUEL_TYPE
    val INFO_EV_CONNECTOR_TYPE: Int = VehiclePropertyIds.INFO_EV_CONNECTOR_TYPE
}
