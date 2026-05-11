package com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayBrightnessEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fake implementation for mock builds. Allows tests and mock flavor to
 * emit display state and brightness events programmatically.
 */
class FakeDisplayStateSource : DisplayStateSource {

    private val _stateEvents = MutableSharedFlow<DisplayStateEvent>(extraBufferCapacity = 16)
    private val _brightnessEvents = MutableSharedFlow<DisplayBrightnessEvent>(extraBufferCapacity = 16)

    override fun observeDisplayStates(): Flow<DisplayStateEvent> = _stateEvents.asSharedFlow()

    override fun observeDisplayBrightness(): Flow<DisplayBrightnessEvent> = _brightnessEvents.asSharedFlow()

    override fun start() { /* no-op */ }

    override fun stop() { /* no-op */ }

    /**
     * Emit a display state event for testing or mock simulation.
     */
    fun emitState(event: DisplayStateEvent) {
        _stateEvents.tryEmit(event)
    }

    /**
     * Convenience: emit a state change for a named display.
     */
    fun emitState(
        display: String,
        state: String,
        previousState: String? = null,
        trigger: String? = null,
    ) {
        _stateEvents.tryEmit(
            DisplayStateEvent(
                display = display,
                state = state,
                previousState = previousState,
                trigger = trigger,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Emit a brightness change event for testing or mock simulation.
     */
    fun emitBrightness(event: DisplayBrightnessEvent) {
        _brightnessEvents.tryEmit(event)
    }
}
