package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.IStandbyModeCallback;

/**
 * Registration data for standby mode callbacks.
 */
parcelable StandbyModeCallbackRegistrationData {
    String displayName;
    String callerId;
    IStandbyModeCallback standbyModeCallback;
}
