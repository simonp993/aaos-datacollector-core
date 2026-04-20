---
adr: ADR-005
title: "Flavor-Based DI Binding Strategy"
status: Accepted
date: 2026-04-20
superseded_by: null
tags: [di, testing, build]
relates_to: [ADR-001, ADR-003]
---

# ADR-005: Flavor-Based DI Binding Strategy

## Context

The build system defines a `datasource` flavor dimension with two values: `mock` (fake/stubbed data) and `real` (live VHAL signals, real services). A mechanism is needed to swap Hilt DI bindings based on flavor so that mock and real builds have genuinely different behavior.

Per ADR-001, library modules (`vehicle-*`, `core-*`) use the `datacollector.android-library` convention plugin which declares no product flavors. Flavor-specific source sets (`src/mock/`, `src/real/`) are only available in the `:app` module.

## Decision

**We will use flavor source sets in `:app`** because (1) `:app` is already the Hilt composition root and the only module with product flavor source sets; (2) it eliminates runtime branching — each flavor has a distinct module graph resolved at compile time; and (3) library modules remain flavor-agnostic.

The convention is:

- **Library modules** define interfaces and implementations but **do not** use `@InstallIn` on modules whose bindings differ by flavor.
- **`:app` module** contains flavor-specific Hilt modules:
  - `app/src/mock/kotlin/.../di/` — modules that bind fake implementations.
  - `app/src/real/kotlin/.../di/` — modules that bind real implementations.
  - `app/src/main/kotlin/.../di/` — modules for bindings identical across flavors.

### Edge-Only Mock Principle

The `mock` flavor must mock only at **external boundaries** — vehicle-platform (VHAL), network, persistence. App-internal interfaces that delegate to an already-mocked external boundary must use their real implementation in both flavors. When the real and mock bindings are identical, the binding lives in `app/src/main/` — not duplicated across `app/src/mock/` and `app/src/real/`.

## Consequences

### Positive

- `mib4MockDebug` and `mib4RealDebug` builds have genuinely different behavior.
- Library modules remain flavor-agnostic with no additional build variants.
- Compile-time graph resolution — Hilt validates the complete binding set per variant.

### Negative / Trade-offs

- Adding a new external boundary with real/fake alternatives requires adding bindings in two `:app` source sets.
- `:app` module's DI surface grows with each new boundary — mitigated by per-domain module files (e.g., `VehiclePlatformBindingsModule`).

### Constraints on future decisions

- Library modules must not use `@InstallIn` on any `@Module` that provides a binding that differs by flavor.
- Flavor-invariant bindings (e.g., `core-logging` providing `LogcatLogger`) may remain `@InstallIn` in the library module.
- Bindings move to flavor source sets only when they actually differ between flavors.
