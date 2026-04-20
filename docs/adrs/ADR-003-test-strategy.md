---
adr: ADR-003
title: "Test Strategy"
status: Accepted
date: 2026-04-20
superseded_by: null
tags: [testing]
relates_to: [ADR-001, ADR-005]
---

# ADR-003: Test Strategy

## Context

The data collector runs as a system-privileged headless service consuming vehicle signals unavailable outside physical hardware. Tests must exercise the full collector and service logic without hardware, while still providing confidence that signal-dependent code works correctly on target devices.

The modular architecture (ADR-001) creates natural test boundaries: `vehicle-*` modules abstract platform APIs, `core-*` provide shared test infrastructure, and collectors in `app/` combine these.

## Decision

**We will follow a test pyramid strategy with ~80% unit / ~15% integration / ~5% emulator tests.**

| Tier | Coverage Target | Tools | What It Tests |
|------|----------------|-------|---------------|
| Unit | ~80% | JUnit 5, Turbine, MockK | Collector logic, service adapters, data mapping |
| Integration | ~15% | Hilt test modules, faked services | Full collector chain with injected fakes |
| Emulator | ~5% | AAOS emulator | Device-specific signal flow, permission grants |

### Key conventions

- **JUnit 5** is the default for all tests. JUnit 4 is permitted only when Robolectric is required.
- **Mock only external boundaries** (VHAL, network, persistence) — never mock app-internal interfaces whose real implementations depend on already-mocked edges (ADR-005 edge-only mock principle).
- **`core-testing/`** is the single source for test infrastructure: `CoroutineTestRule`, Turbine extensions, and test utilities.
- **JaCoCo ≥80% line coverage** per module, enforced as a CI gate.

## Consequences

### Positive

- Fast CI feedback: `testMib4MockDebugUnitTest` runs the full unit suite in under 60 seconds.
- Hardware-independent development: all unit and integration tests run on a laptop.
- Deterministic signal testing via `CoroutineTestRule` with `UnconfinedTestDispatcher` and Turbine.
- Coverage enforcement: modules below 80% fail the build.

### Negative / Trade-offs

- Fake service implementations must be maintained alongside real ones.
- Emulator coverage is thin — device-specific edge cases may escape automated tests.
- Turbine learning curve for developers unfamiliar with coroutine test dispatchers.

### Constraints on future decisions

- Every new module must include a `testMib4MockDebugUnitTest` task meeting the 80% JaCoCo threshold.
- Service interfaces must be designed for fake-ability.
- `core-testing/` is the single source for test infrastructure — no competing rules or assertion libraries in modules.
