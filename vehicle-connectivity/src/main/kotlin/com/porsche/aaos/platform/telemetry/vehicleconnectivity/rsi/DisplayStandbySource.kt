package com.porsche.aaos.platform.telemetry.vehicleconnectivity.rsi

import kotlinx.coroutines.flow.Flow

/**
 * Observes display standby activation and mode changes via the EsoCarStandbyService AIDL.
 *
 * On MIB4 Cayenne, the passenger display (PID) supports standby mode with two variants:
 * - VEHICLEMODEL (mode 0): shows a vehicle model standby screen
 * - MEDIA (mode 1): shows media info on the standby screen
 *
 * This source is generic and supports any display that the standby service reports.
 */
interface DisplayStandbySource {
    fun observeStandbyState(): Flow<DisplayStandbyEvent>
    fun start()
    fun stop()
}

/**
 * Represents a standby state change for a display.
 *
 * @param display Logical display name: "passenger" (mapped from "PID"), "center" (from "CID")
 * @param active Whether standby is currently active on this display
 * @param mode Current standby mode when active: "vehiclemodel", "media", "myscreen", "drivemode"
 * @param disabled Whether standby is restricted (cannot be activated)
 * @param modeChangeOnly True if this event only signals a mode change (not an on/off toggle)
 * @param timestamp Unix millis when the change was detected
 */
data class DisplayStandbyEvent(
    val display: String,
    val active: Boolean,
    val mode: String?,
    val disabled: Boolean,
    val modeChangeOnly: Boolean = false,
    val timestamp: Long,
)
