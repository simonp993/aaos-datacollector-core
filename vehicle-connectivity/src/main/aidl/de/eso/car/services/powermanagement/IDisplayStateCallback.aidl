package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.DisplayStateCallbackData;

/**
 * Callback interface for display standby on/off state changes.
 */
interface IDisplayStateCallback {
    const String DISPLAY_NAME_CID = "CID";
    const String DISPLAY_NAME_PID = "PID";

    oneway void onUpdateDisplayState(in DisplayStateCallbackData displayStateCallbackData) = 0;
}
