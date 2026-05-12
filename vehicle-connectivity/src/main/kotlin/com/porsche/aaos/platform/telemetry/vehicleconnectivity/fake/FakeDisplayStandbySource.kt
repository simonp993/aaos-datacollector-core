package com.porsche.aaos.platform.telemetry.vehicleconnectivity.fake

import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStandbyEvent
import com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi.DisplayStandbySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fake implementation for mock builds. Allows tests and mock flavor to
 * emit display standby events programmatically.
 */
class FakeDisplayStandbySource : DisplayStandbySource {

    private val _events = MutableSharedFlow<DisplayStandbyEvent>(extraBufferCapacity = 16)

    override fun observeStandbyState(): Flow<DisplayStandbyEvent> = _events.asSharedFlow()

    override fun start() { /* no-op */ }

    override fun stop() { /* no-op */ }

    /**
     * Emit a standby event for testing or mock simulation.
     */
    fun emitStandby(event: DisplayStandbyEvent) {
        _events.tryEmit(event)
    }

    /**
     * Convenience: emit a standby state change for a named display.
     */
    fun emitStandby(
        display: String,
        active: Boolean,
        mode: String? = null,
        disabled: Boolean = false,
    ) {
        _events.tryEmit(
            DisplayStandbyEvent(
                display = display,
                active = active,
                mode = mode,
                disabled = disabled,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }
}
