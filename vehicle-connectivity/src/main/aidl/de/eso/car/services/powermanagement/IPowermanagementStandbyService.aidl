package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.DisplayStandbyOnOffRequestData;
import de.eso.car.services.powermanagement.DisplayStateCallbackRegistrationData;
import de.eso.car.services.powermanagement.StandbyModeCallbackRegistrationData;
import de.eso.car.services.powermanagement.DisplayStandbyModeRequestData;

/**
 * AIDL interface for SystemUI standby service.
 * Requires signature|privileged permission: de.eso.car.permission.POWERMANAGEMENT_STANDBY
 */
interface IPowermanagementStandbyService {
    const int VERSION = 1;

    int getIfcVersion() = 0;

    void requestDisplayStandbyOn(in DisplayStandbyOnOffRequestData requestData) = 1;

    void requestDisplayStandbyOff(in DisplayStandbyOnOffRequestData requestData) = 2;

    void registerDisplayStandbyOnOffStateCallback(
        in DisplayStateCallbackRegistrationData callbackData,
        in IBinder clientDeathListener) = 3;

    void unregisterDisplayStandbyOnOffStateCallback(
        in DisplayStateCallbackRegistrationData callbackData) = 4;

    void registerDisplayStandbyModeCallback(
        in StandbyModeCallbackRegistrationData callbackData,
        in IBinder clientDeathListener) = 5;

    void unregisterDisplayStandbyModeCallback(
        in StandbyModeCallbackRegistrationData callbackData) = 6;

    void requestDisplayStandbyMode(in DisplayStandbyModeRequestData requestData) = 7;
}
