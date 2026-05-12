package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.StandbyMode;

/**
 * Callback data for standby mode changes.
 */
parcelable StandbyModeCallbackData {
    String displayName;
    StandbyMode activeStandbyMode;
    StandbyMode[] availableStandbyModes;
}
