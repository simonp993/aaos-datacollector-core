package com.porsche.aaos.platform.telemetry.collector.system

import com.porsche.aaos.platform.telemetry.collector.Collector
import com.porsche.aaos.platform.telemetry.core.logging.Logger
import com.porsche.aaos.platform.telemetry.telemetry.Telemetry
import com.porsche.aaos.platform.telemetry.telemetry.TelemetryEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayBrightnessEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Collects display state (on/off) and brightness changes following the Audio_StateChanged pattern.
 *
 * Event types:
 * - **Display_StateChanged**: all display on/off/standby states + which changed
 * - **Display_BrightnessChanged**: all display brightness levels + which changed
 * - **Display_StateSnapshot**: periodic heartbeat of all display states
 * - **Display_BrightnessSnapshot**: periodic heartbeat of all brightness levels
 *
 * Each "changed" event includes full current state of ALL displays and the
 * previous state of the changed display(s). Periodic snapshots emit full state.
 *
 * On MIB4, display power and brightness are managed via RSI:
 * - MCP_Popups DISPLAY_OFF_POPUP (center, passenger, rear)
 * - HeadUpDisplay switchControls (HUD on/off)
 * - HIDService valueControls (center/passenger/console brightness, -5..5)
 * - InstrumentClusterConfiguration valueControls (IC brightness, -5..5)
 */
class DisplayStateCollector @Inject constructor(
    private val displayStateSource: DisplayStateSource,
    private val telemetry: Telemetry,
    private val logger: Logger,
) : Collector {

    override val name: String = "DisplayState"
    private val signalId = TelemetryEvent.signalId("${name}Collector")

    @Volatile
    private var running = false
    private var stateJob: Job? = null
    private var brightnessJob: Job? = null

    // Full state maps — always contain ALL known displays
    private var displayStates: MutableMap<String, String> = mutableMapOf()
    private var displayBrightness: MutableMap<String, Int> = mutableMapOf()

    // Previous state snapshots for diff detection
    private var lastEmittedStates: Map<String, String>? = null
    private var lastEmittedBrightness: Map<String, Int>? = null

    override suspend fun start() {
        running = true
        logger.i(TAG, "Starting DisplayStateCollector")

        // Initialize default states (all on until RSI tells us otherwise)
        initializeStates()

        // Start RSI display state observation
        displayStateSource.start()
        stateJob = CoroutineScope(Dispatchers.IO).launch {
            displayStateSource.observeDisplayStates().collect { event ->
                handleDisplayStateChange(event)
            }
        }

        // Start RSI brightness observation
        brightnessJob = CoroutineScope(Dispatchers.IO).launch {
            displayStateSource.observeDisplayBrightness().collect { event ->
                handleBrightnessChange(event)
            }
        }

        // Emit initial snapshot
        emitStateSnapshot()
        emitBrightnessSnapshot()

        // Periodic heartbeat every 60s
        while (running && coroutineContext.isActive) {
            delay(HEARTBEAT_MS)
            emitStateSnapshot()
            emitBrightnessSnapshot()
        }
    }

    override fun stop() {
        running = false
        stateJob?.cancel()
        stateJob = null
        brightnessJob?.cancel()
        brightnessJob = null
        displayStateSource.stop()
        logger.i(TAG, "DisplayStateCollector stopped")
    }

    private fun initializeStates() {
        STATE_DISPLAYS.forEach { display ->
            displayStates[display] = STATE_ON
        }
    }

    // --- Display State (on/off) ---

    private fun handleDisplayStateChange(event: DisplayStateEvent) {
        val previous = displayStates.toMap()
        displayStates[event.display] = event.state

        if (displayStates == lastEmittedStates) return

        lastEmittedStates = displayStates.toMap()
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_STATE_CHANGED,
                    "trigger" to (event.trigger ?: "unknown"),
                    "metadata" to mapOf(
                        "previous" to previous,
                        "current" to displayStates.toMap(),
                        "changed" to event.display,
                    ),
                ),
                timestamp = event.timestamp,
            ),
        )
    }

    private fun emitStateSnapshot() {
        lastEmittedStates = displayStates.toMap()
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_STATE_SNAPSHOT,
                    "trigger" to "heartbeat",
                    "metadata" to displayStates.toMap(),
                ),
            ),
        )
    }

    // --- Display Brightness (via RSI) ---

    private fun handleBrightnessChange(event: DisplayBrightnessEvent) {
        val previous = displayBrightness.toMap()
        displayBrightness[event.display] = event.value

        if (displayBrightness == lastEmittedBrightness) return

        lastEmittedBrightness = displayBrightness.toMap()
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_BRIGHTNESS_CHANGED,
                    "trigger" to "user",
                    "metadata" to mapOf(
                        "previous" to previous,
                        "current" to displayBrightness.toMap(),
                        "changed" to event.display,
                    ),
                ),
                timestamp = event.timestamp,
            ),
        )
    }

    private fun emitBrightnessSnapshot() {
        if (displayBrightness.isEmpty()) return
        lastEmittedBrightness = displayBrightness.toMap()
        telemetry.send(
            TelemetryEvent(
                signalId = signalId,
                payload = mapOf(
                    "actionName" to ACTION_BRIGHTNESS_SNAPSHOT,
                    "trigger" to "heartbeat",
                    "metadata" to displayBrightness.toMap(),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "DisplayStateCollector"
        private const val HEARTBEAT_MS = 60_000L

        private const val ACTION_STATE_CHANGED = "Display_StateChanged"
        private const val ACTION_STATE_SNAPSHOT = "Display_StateSnapshot"
        private const val ACTION_BRIGHTNESS_CHANGED = "Display_BrightnessChanged"
        private const val ACTION_BRIGHTNESS_SNAPSHOT = "Display_BrightnessSnapshot"

        private const val STATE_ON = "on"

        // Displays that have on/off state (not all have brightness)
        private val STATE_DISPLAYS = listOf("center", "passenger", "hud")
    }
}
