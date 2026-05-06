package com.porsche.aaos.platform.telemetry.di

import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyIds
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
import com.porsche.aaos.platform.telemetry.vehicleplatform.fake.FakeVhalPropertyService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object MockVehiclePlatformModule {
    @Provides
    @Singleton
    fun provideVhalPropertyService(): VhalPropertyService = FakeVhalPropertyService().apply {
        runBlocking {
            // Vehicle Info (static)
            emit(VhalPropertyIds.INFO_VIN, "WP0ZZZ99ZRS123456")
            emit(VhalPropertyIds.INFO_MAKE, "Porsche")
            emit(VhalPropertyIds.INFO_MODEL, "Taycan")
            emit(VhalPropertyIds.INFO_MODEL_YEAR, 2026)
            emit(VhalPropertyIds.PORSCHE_DIAG_MMTR_AVAILABLE, 1)
            // Driving / Powertrain
            emit(VhalPropertyIds.PERF_VEHICLE_SPEED, 0.0f)
            emit(VhalPropertyIds.ENGINE_RPM, 750.0f)
            emit(VhalPropertyIds.GEAR_SELECTION, 4) // PARK
            emit(VhalPropertyIds.CURRENT_GEAR, 1)
            emit(VhalPropertyIds.FUEL_LEVEL, 45.0f)
            emit(VhalPropertyIds.FUEL_LEVEL_LOW, false)
            emit(VhalPropertyIds.EV_BATTERY_LEVEL, 78.0f)
            emit(VhalPropertyIds.IGNITION_STATE, 4) // ON
            emit(VhalPropertyIds.PARKING_BRAKE_ON, true)
            // Body / Exterior
            emit(VhalPropertyIds.DOOR_LOCK, true)
            emit(VhalPropertyIds.HEADLIGHTS_STATE, 1) // ON
            emit(VhalPropertyIds.TURN_SIGNAL_STATE, 0) // NONE
            emit(VhalPropertyIds.NIGHT_MODE, false)
            emit(VhalPropertyIds.TIRE_PRESSURE, 240.0f)
            // Cabin / Comfort
            emit(VhalPropertyIds.ENV_OUTSIDE_TEMPERATURE, 18.5f)
            emit(VhalPropertyIds.DISPLAY_BRIGHTNESS, 80)
            emit(VhalPropertyIds.CABIN_LIGHTS_STATE, 0) // OFF
            emit(VhalPropertyIds.SEAT_BELT_BUCKLED, true)
            emit(VhalPropertyIds.SEAT_OCCUPANCY, 1) // OCCUPIED
            emit(VhalPropertyIds.ABS_ACTIVE, false)
            emit(VhalPropertyIds.TRACTION_CONTROL_ACTIVE, false)
            // HVAC / Climate
            emit(VhalPropertyIds.HVAC_FAN_SPEED, 3)
            emit(VhalPropertyIds.HVAC_FAN_DIRECTION, 1)
            emit(VhalPropertyIds.HVAC_FAN_DIRECTION_AVAILABLE, listOf(1, 2, 3))
            emit(VhalPropertyIds.HVAC_ACTUAL_FAN_SPEED_RPM, 800)
            emit(VhalPropertyIds.HVAC_TEMPERATURE_SET, 22.0f)
            emit(VhalPropertyIds.HVAC_TEMPERATURE_CURRENT, 21.5f)
            emit(VhalPropertyIds.HVAC_AC_ON, true)
            emit(VhalPropertyIds.HVAC_MAX_AC_ON, false)
            emit(VhalPropertyIds.HVAC_DEFROSTER, false)
            emit(VhalPropertyIds.HVAC_ELECTRIC_DEFROSTER_ON, false)
            emit(VhalPropertyIds.HVAC_MAX_DEFROST_ON, false)
            emit(VhalPropertyIds.HVAC_RECIRC_ON, false)
            emit(VhalPropertyIds.HVAC_AUTO_RECIRC_ON, true)
            emit(VhalPropertyIds.HVAC_DUAL_ON, true)
            emit(VhalPropertyIds.HVAC_AUTO_ON, true)
            emit(VhalPropertyIds.HVAC_POWER_ON, true)
            emit(VhalPropertyIds.HVAC_SEAT_TEMPERATURE, 0)
            emit(VhalPropertyIds.HVAC_SEAT_VENTILATION, 0)
            emit(VhalPropertyIds.HVAC_STEERING_WHEEL_HEAT, 0)
            emit(VhalPropertyIds.HVAC_SIDE_MIRROR_HEAT, 0)
            emit(VhalPropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS, 1)
            emit(VhalPropertyIds.HVAC_TEMPERATURE_VALUE_SUGGESTION, listOf(22.0f, 71.6f, 22.0f, 71.6f))
        }
    }
}
