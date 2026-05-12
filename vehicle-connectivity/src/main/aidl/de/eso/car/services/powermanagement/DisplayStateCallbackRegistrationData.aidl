package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.IDisplayStateCallback;

/**
 * Registration data for display standby on/off state callbacks.
 */
parcelable DisplayStateCallbackRegistrationData {
    String displayName;
    String callerId;
    IDisplayStateCallback displayStateCallback;
}
