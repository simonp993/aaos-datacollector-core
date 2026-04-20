package com.porsche.datacollector.collector.vehicle

import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.vehicleplatform.VhalPropertyIds
import com.porsche.datacollector.vehicleplatform.VhalPropertyService
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class VehiclePropertyCollector @Inject constructor(
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "VehicleProperty"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        coroutineScope {
            OBSERVED_PROPERTIES.forEach { (propertyId, propertyName) ->
                launch {
                    vhalPropertyService.observeProperty<Any>(propertyId)
                        .catch { e ->
                            logger.w(TAG, "Failed to observe $propertyName ($propertyId)", e)
                        }
                        .collect { value ->
                            if (!running) return@collect
                            telemetry.send(
                                TelemetryEvent(
                                    eventId = "vehicle.$propertyName",
                                    payload = mapOf(
                                        "propertyId" to propertyId,
                                        "value" to value,
                                    ),
                                ),
                            )
                        }
                }
            }
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "VehiclePropertyCollector"

        private val OBSERVED_PROPERTIES = listOf(
            VhalPropertyIds.PERF_VEHICLE_SPEED to "speed",
            VhalPropertyIds.ENGINE_RPM to "rpm",
            VhalPropertyIds.GEAR_SELECTION to "gear",
            VhalPropertyIds.FUEL_LEVEL to "fuel_level",
            VhalPropertyIds.EV_BATTERY_LEVEL to "ev_battery_level",
            VhalPropertyIds.HVAC_TEMPERATURE_SET to "hvac_temp",
            VhalPropertyIds.DOOR_LOCK to "door_lock",
            VhalPropertyIds.HEADLIGHTS_STATE to "headlights",
            VhalPropertyIds.TURN_SIGNAL_STATE to "turn_signal",
        )
    }
}
