package de.eso.car.services.powermanagement;

/**
 * Callback data for display standby on/off state changes.
 */
parcelable DisplayStateCallbackData {
    String displayName;
    boolean displayAvailable;
    boolean requestedStateActive;
    boolean requestedStateDisabled;
}
