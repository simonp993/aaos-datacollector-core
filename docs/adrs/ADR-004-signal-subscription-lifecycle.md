---
adr: ADR-004
title: "Signal Subscription Lifecycle Pattern"
status: Accepted
date: 2026-04-20
superseded_by: null
tags: [vehicle-signals]
relates_to: [ADR-002]
---

# ADR-004: Signal Subscription Lifecycle Pattern

## Context

Vehicle signal subscriptions create persistent connections to ECUs via the ASI service layer. Subscribing and unsubscribing triggers IPC to the vehicle abstraction daemon, which negotiates CAN/FlexRay subscriptions. The data collector service may observe dozens of signals simultaneously.

Unlike a UI app with screen navigation, the data collector runs continuously as a foreground service. The lifecycle pattern determines when ECU resources are acquired and released.

## Decision

**We will use service-scoped subscriptions with `WhileSubscribed`** for signal-bearing collectors. Since the service runs `START_STICKY`, subscriptions are effectively application-scoped but still use `shareIn` with `WhileSubscribed(5_000)` to handle transient service restarts gracefully.

```kotlin
// In vehicle service adapter
private val vehicleSpeed: SharedFlow<Float?> =
    carPropertyService.observeProperty(VhalPropertyIds.PERF_VEHICLE_SPEED)
        .map { it.value as? Float }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
```

Key conventions:

- **State signals** (speed, RPM, temperatures): `replay = 1` so new collectors receive the latest value immediately.
- **Event signals** (command acknowledgements, errors): `replay = 0` to avoid replaying stale events.
- **Grace period**: 5 seconds absorbs transient service restarts without triggering ECU resubscription.

## Consequences

### Positive

- Automatic lifecycle — no manual subscribe/unsubscribe calls.
- Multiple collectors sharing the same signal share a single underlying subscription.
- `replay = 1` prevents stale-data gaps when collectors restart.
- On vehicle disconnect, the underlying `callbackFlow` completes; new collectors trigger automatic re-subscription.

### Negative / Trade-offs

- ECU resources remain allocated for up to 5s after the last collector stops. Acceptable for a continuously running service.
- Debugging shared `Flow` chains is harder than direct subscriptions.

### Constraints on future decisions

- New signal-consuming code must follow this `WhileSubscribed` pattern.
- `vehicle-connectivity/` must surface `Flow` types via subscriber interfaces, never raw ASI callback handles.
