# Data Collector Core — Copilot Instructions

## Product Context

aaos-datacollector-core is a system-privileged headless data collection service for Porsche AAOS infotainment systems. It collects vehicle, system, media, network, and sensor data via a pluggable collector architecture and sends it through an abstracted telemetry interface.

## Platform and Scope

- Target platform: Android Automotive OS (AOSP 13 MIB4 + AOSP 15 next-gen)
- Headless service — no UI, no Compose, no Activities
- System-privileged — signed with AOSP platform key for signature-level permissions
- Primary stack: Kotlin, Coroutines, Hilt, AAOS Car APIs

## Architecture

```
app/                    — Service host, collectors, DI, telemetry
core-logging/           — Logger interface
core-common/            — Shared utilities
core-testing/           — Test infrastructure
vehicle-platform/       — VHAL property observation
vehicle-connectivity/   — ASI/RSI signal subscription
```

## Build Toolchain

- Java 21 toolchain, compileSdk 36, minSdk 33, targetSdk 35
- Build flavors: `platform` (mib4, nextgen) × `datasource` (mock, real)
- Convention plugins: `datacollector.android-app`, `sportapps.android-library`, `sportapps.kotlin-library`
- Formatting: Spotless + ktlint
- Static analysis: Detekt (zero-tolerance)
- Coverage: JaCoCo ≥80% per module

## Code Style

- Trailing commas required
- Max line length: 120 characters
- Wildcard imports disallowed
- JUnit 5 is the default test framework
- MockK for mocking, Turbine for Flow testing

## DI Patterns

- Hilt is the DI framework
- Flavor-specific modules in `app/src/mock/` and `app/src/real/`
- Bindings identical across flavors belong in `app/src/main/`
- Mock only external boundaries (VHAL, network) — not app-internal interfaces

## Testing

- JUnit 5 default, JaCoCo ≥80% line coverage
- Run tests: `./gradlew testMib4MockDebugUnitTest`
- Use `mockk` for mocking, `turbine` for Flow testing

## ADRs

- ADRs in `docs/adrs/` are binding. Cite ADR numbers when relevant.
- If a change conflicts with an ADR, raise it explicitly.

---

**Last Updated**: 2026-04-20
