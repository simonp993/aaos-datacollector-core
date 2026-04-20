package com.porsche.datacollector.collector.vehicle

import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import com.porsche.sportapps.vehicleplatform.VhalPropertyIds
import com.porsche.sportapps.vehicleplatform.VhalPropertyService
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DriveStateCollector @Inject constructor(
    private val vhalPropertyService: VhalPropertyService,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "DriveState"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        coroutineScope {
            launch { observeSpeed() }
            launch { observeParkingBrake() }
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    private suspend fun observeSpeed() {
        vhalPropertyService.observeProperty<Float>(VhalPropertyIds.PERF_VEHICLE_SPEED)
            .catch { e -> logger.w(TAG, "Failed to observe vehicle speed", e) }
            .collect { speed ->
                if (!running) return@collect
                telemetry.send(
                    TelemetryEvent(
                        eventId = "drive.speed",
                        payload = mapOf(
                            "speedMps" to speed,
                            "moving" to (speed > 0f),
                        ),
                    ),
                )
            }
    }

    private suspend fun observeParkingBrake() {
        vhalPropertyService.observeProperty<Boolean>(VhalPropertyIds.PARKING_BRAKE_ON)
            .catch { e -> logger.w(TAG, "Failed to observe parking brake", e) }
            .collect { parked ->
                if (!running) return@collect
                telemetry.send(
                    TelemetryEvent(
                        eventId = "drive.parking_brake",
                        payload = mapOf("parked" to parked),
                    ),
                )
            }
    }

    companion object {
        private const val TAG = "DriveStateCollector"
    }
}
