package com.porsche.aaos.platform.telemetry.collector.vehicle

import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyIds
import com.porsche.aaos.platform.telemetry.vehicleplatform.VhalPropertyService
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
    private val signalId = TelemetryEvent.signalId("${name}Collector")

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
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "DriveState_SpeedChanged",
                            "metadata" to mapOf(
                                "speedMps" to speed,
                                "moving" to (speed > 0f),
                            ),
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
                        signalId = signalId,
                        payload = mapOf(
                            "actionName" to "DriveState_ParkingBrakeChanged",
                            "metadata" to mapOf("parked" to parked),
                        ),
                    ),
                )
            }
    }

    companion object {
        private const val TAG = "DriveStateCollector"
    }
}
