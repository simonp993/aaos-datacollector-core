---
adr: ADR-002
title: "Car Connectivity Abstraction Level"
status: Accepted
date: 2026-04-20
superseded_by: null
tags: [vehicle-signals]
relates_to: [ADR-001, ADR-004]
---

# ADR-002: Car Connectivity Abstraction Level

## Context

The data collector consumes vehicle data from three protocols: VHAL (CarPropertyManager), ASI (service binding for commands/callbacks), and RSI (telemetry signal subscriptions). A single collector may combine data from multiple protocols.

If collector code addresses each protocol directly, protocol knowledge leaks into collection logic, making collectors hard to test and brittle when protocol details change.

**Signal quality contract**: Across all protocols, invalid or unavailable signals must be mapped to `null` at the abstraction boundary. Stale values must never be cached or forwarded.

## Decision

**We will use repository-style abstractions** where each collector depends on interface-level abstractions (e.g., `VhalPropertyService`) rather than raw protocol APIs. Adapter implementations in infrastructure modules combine and translate protocol-specific types into clean domain types.

Key principles:

1. **Testability** — Collectors test against simple fake services that emit predetermined `Flow<T>` sequences. No vehicle hardware or emulator required.
2. **Protocol encapsulation** — Mixing signals from ASI, RSI, and VHAL is the responsibility of adapter/service classes in `vehicle-*` modules.
3. **Domain purity** — Service interfaces are pure Kotlin, keeping collector logic free of Android framework dependencies.

## Consequences

### Positive

- Each collector injects only the service interfaces it needs via constructor injection.
- The `null`-for-invalid signal quality contract is enforced inside adapters, not in collector code.
- `vehicle-connectivity/` remains a pure infrastructure module with no collector-specific logic.

### Negative / Trade-offs

- Each new signal source requires writing both an interface and at least one adapter implementation.
- Adapter tests must verify correct protocol-to-domain mapping, including signal-quality-to-`null` contract.

### Constraints on future decisions

- `vehicle-connectivity/` must not contain collector-specific logic.
- If a signal is consumed by multiple collectors, each still depends on shared service interfaces in `vehicle-*` modules.
