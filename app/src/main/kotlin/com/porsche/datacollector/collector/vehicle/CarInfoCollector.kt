package com.porsche.datacollector.collector.vehicle

import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.datacollector.core.logging.Logger
import com.porsche.datacollector.vehicleplatform.VhalPropertyIds
import com.porsche.datacollector.vehicleplatform.VhalPropertyService
import javax.inject.Inject

class CarInfoCollector @Inject constructor(
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "CarInfo"

    override suspend fun start() {
        logger.i(TAG, "Collecting car info (one-shot)")

        val info = mutableMapOf<String, Any?>()

        readProperty<String>(VhalPropertyIds.INFO_VIN, "vin")?.let { info["vin"] = it }
        readProperty<String>(VhalPropertyIds.INFO_MAKE, "make")?.let { info["make"] = it }
        readProperty<String>(VhalPropertyIds.INFO_MODEL, "model")?.let { info["model"] = it }
        readProperty<Int>(VhalPropertyIds.INFO_MODEL_YEAR, "model_year")?.let { info["model_year"] = it }
        readProperty<IntArray>(VhalPropertyIds.INFO_FUEL_TYPE, "fuel_type")?.let { info["fuel_type"] = it }
        readProperty<IntArray>(VhalPropertyIds.INFO_EV_CONNECTOR_TYPE, "ev_connector")?.let { info["ev_connector"] = it }

        telemetry.send(
            TelemetryEvent(
                eventId = "car.info",
                payload = info,
            ),
        )
        logger.i(TAG, "Car info collected: ${info.keys}")
    }

    override fun stop() {
        // One-shot collector — nothing to stop
    }

    private fun <T : Any> readProperty(propertyId: Int, label: String): T? = try {
        vhalPropertyService.readProperty<T>(propertyId)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.w(TAG, "Failed to read $label ($propertyId)", e)
        null
    }

    companion object {
        private const val TAG = "CarInfoCollector"
    }
}
