package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import kotlinx.coroutines.flow.Flow

/**
 * Emits display on/off/standby state changes and brightness changes for all vehicle displays.
 *
 * On MIB4 Cayenne the displays are managed via RSI:
 * - MCP_Popups DISPLAY_OFF_POPUP for center, passenger, rear on/off/standby
 * - HeadUpDisplay switchControls for HUD on/off
 * - HIDService valueControls for center/passenger/console brightness (-5..5)
 * - InstrumentClusterConfiguration valueControls for IC brightness (-5.0..5.0)
 *
 * Android's DisplayManager.STATE_* and Settings.System.SCREEN_BRIGHTNESS
 * do not reflect actual display state or brightness on this hardware.
 */
interface DisplayStateSource {
    fun observeDisplayStates(): Flow<DisplayStateEvent>
    fun observeDisplayBrightness(): Flow<DisplayBrightnessEvent>
    fun start()
    fun stop()
}

/**
 * Represents a single display state transition.
 *
 * @param display Logical display name: "center", "passenger", "hud", "rear"
 * @param state New state: "on", "off", "standby"
 * @param previousState Previous state if known, null on first emission
 * @param trigger What caused the transition (from RSI requestData):
 *   "displayOff", "displayOn", "touchInput", "system", or null if unknown
 * @param timestamp Unix millis when the state change was detected
 */
data class DisplayStateEvent(
    val display: String,
    val state: String,
    val previousState: String?,
    val trigger: String?,
    val timestamp: Long,
)

/**
 * Represents a brightness change for a single display.
 *
 * @param display Logical display name: "center", "passenger", "console", "cluster"
 * @param value Current brightness offset value (typically -5..5)
 * @param previousValue Previous brightness value if known
 * @param minValue Minimum allowed value
 * @param maxValue Maximum allowed value
 * @param timestamp Unix millis when the brightness change was detected
 */
data class DisplayBrightnessEvent(
    val display: String,
    val value: Int,
    val previousValue: Int?,
    val minValue: Int,
    val maxValue: Int,
    val timestamp: Long,
)
