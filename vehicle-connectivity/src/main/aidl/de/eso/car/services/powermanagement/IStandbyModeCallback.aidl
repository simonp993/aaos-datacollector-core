package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.StandbyModeCallbackData;

/**
 * Callback interface for display standby mode changes.
 */
interface IStandbyModeCallback {
    const String DISPLAY_NAME_PID = "PID";

    oneway void onUpdateStandbyMode(in StandbyModeCallbackData standbyModeCallbackData) = 0;
}
