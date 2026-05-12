package de.eso.car.services.powermanagement;

import de.eso.car.services.powermanagement.StandbyMode;

parcelable DisplayStandbyModeRequestData {
    String displayName;
    String callerId;
    StandbyMode activeStandbyMode;
}
