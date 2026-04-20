---
adr: ADR-001
title: "Module Structure"
status: Accepted
date: 2026-04-20
superseded_by: null
tags: [modules, dependencies]
relates_to: [ADR-002, ADR-003]
---

# ADR-001: Module Structure

## Context

The data collector is a headless service that collects vehicle, system, and telemetry data from multiple sources. Without explicit module boundaries, collector implementations will couple directly to platform APIs, making testing and platform migration difficult.

The service targets two platform generations (AOSP 13 MIB4, AOSP 15 next-gen) with different VHAL and connectivity APIs. A modular structure lets us isolate platform-specific code behind stable interfaces.

## Decision

**We will use a hybrid module structure** with shared infrastructure modules and a single app module that hosts all collectors.

```
app/                    — Headless service host, Hilt entry point, all collectors, DI modules
core-logging/           — Logger interface + LogcatLogger
core-common/            — Shared Kotlin utilities, coroutine helpers
core-testing/           — JUnit 5 extensions, Turbine helpers (testImplementation only)
vehicle-platform/       — VHAL observation via CarPropertyManager, VhalPropertyIds, VehicleType
vehicle-connectivity/   — ASI service abstraction, RSI signal subscription
```

The dependency graph flows strictly downward:

```
app → vehicle-platform → core-logging, core-common
app → vehicle-connectivity → core-logging, core-common
app → core-logging, core-common
core-testing (testImplementation only for all modules)
```

**`vehicle-platform/` vs `vehicle-connectivity/` boundary**: `vehicle-platform/` owns static vehicle identity and VHAL property observation. `vehicle-connectivity/` owns runtime ASI/RSI signal subscription and command execution. This mirrors the change frequency: VHAL properties are relatively stable reads, while connectivity signals change continuously.

## Consequences

### Positive

- Each module compiles and tests independently.
- `vehicle-*` modules expose only interfaces publicly; concrete implementations are `internal`.
- `core-testing/` is `testImplementation`-only — never ships in production.
- Platform migration (MIB4 → next-gen) is isolated to `vehicle-*` modules.

### Negative / Trade-offs

- All collector implementations live in `app/` rather than separate modules. This is acceptable for a headless service with no feature-level navigation.
- Changes to `vehicle-*` public interfaces trigger `app/` recompilation.

### Constraints on future decisions

- `vehicle-*` modules must expose only interfaces in their public API. Concrete implementations stay module-internal and are bound via Hilt `@Binds`.
- New shared abstractions require updating this ADR's dependency graph.
- `core-testing/` is the single source for test infrastructure.
