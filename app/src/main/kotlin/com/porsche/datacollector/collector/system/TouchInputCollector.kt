package com.porsche.datacollector.collector.system

import android.content.Context
import android.hardware.input.InputManager
import com.porsche.datacollector.collector.Collector
import com.porsche.datacollector.telemetry.Telemetry
import com.porsche.datacollector.telemetry.TelemetryEvent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Monitors touch input events.
 *
 * NOTE: Full InputMonitor API (android.hardware.input.InputMonitor) is a hidden system API
 * requiring signature-level permission MONITOR_INPUT. The implementation approach may differ
 * between mib4 (AOSP 13) and nextgen (AOSP 15) — use platform-specific source sets if needed.
 *
 * Current implementation logs available input devices. Full event capture requires
 * InputMonitor.monitorGestureInput() which is not in the public SDK.
 */
class TouchInputCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "TouchInput"

    @Volatile
    private var running = false

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting touch input monitoring")
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

        // TODO: Platform-specific InputMonitor implementation for full touch event capture.
        //  AOSP 13 (mib4) and AOSP 15 (nextgen) may expose different hidden APIs.
        //  For now, report available input devices.

        while (running && coroutineContext.isActive) {
            val deviceIds = inputManager.inputDeviceIds
            val devices = deviceIds.toList().mapNotNull { id ->
                inputManager.getInputDevice(id)?.let { device ->
                    mapOf(
                        "id" to device.id,
                        "name" to device.name,
                        "sources" to device.sources,
                        "isVirtual" to device.isVirtual,
                    )
                }
            }

            telemetry.send(
                TelemetryEvent(
                    eventId = "input.devices",
                    payload = mapOf("devices" to devices),
                ),
            )

            delay(POLL_INTERVAL_MS)
        }
    }

    override fun stop() {
        running = false
        logger.i(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "TouchInputCollector"
        private const val POLL_INTERVAL_MS = 10_000L
    }
}
