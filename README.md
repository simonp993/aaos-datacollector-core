# aaos-datacollector-core

System-privileged headless data collection service for Porsche AAOS infotainment systems. Runs as a `START_STICKY` foreground service with no UI, collecting vehicle, system, and telemetry data from multiple signal sources.

## Architecture

```
System-privileged headless Service (START_STICKY)
│
├── Collectors (one per signal group, run independently)
│     └── each calls telemetry.send(eventId, payload)
│
├── Telemetry interface
│     └── TelemetryImpl per build flavour
│
└── Build Flavours
      ├── platform:   mib4 (AOSP 13) | nextgen (AOSP 15)
      └── datasource: mock | real
```

## Module Structure

```
app/                    — Headless service host, Hilt entry point, collectors, DI
core-logging/           — Logger interface + LogcatLogger
core-common/            — Shared Kotlin utilities, coroutine helpers
core-testing/           — JUnit 5 extensions, Turbine helpers, test utilities
vehicle-platform/       — VHAL observation via CarPropertyManager, VhalPropertyIds
vehicle-connectivity/   — ASI service abstraction, RSI signal subscription
```

## Signal Groups Collected

| Group | Key API | Trigger |
|---|---|---|
| Vehicle Properties | `CarPropertyManager` (speed, RPM, gear, fuel, HVAC, doors, lights) | Callback |
| Drive State | `CarPropertyManager` (speed, parking brake) | Callback |
| Car Info | `CarPropertyManager` (VIN, make, model, year) | One-shot |
| Media Playback | `MediaSessionManager`, `MediaController` | Callback |
| App Lifecycle | `UsageStatsManager`, `UsageEvents` | Poll |
| Process & PIDs | `ActivityManager.runningAppProcesses` | Poll |
| Memory | `ActivityManager.MemoryInfo` | Poll |
| Network (total + per-app) | `NetworkStatsManager` | Poll |
| Connectivity | `ConnectivityManager.NetworkCallback` | Callback |
| Audio | `AudioManager` (volumes, output devices, mic state) | Poll |
| Telephony | `TelephonyManager`, `PhoneStateListener` | Callback |
| Touch Input | `InputManager` (device inventory) | Poll |
| Sensors & Battery | `SensorManager`, `BatteryManager` | Callback + Poll |
| Packages | `PackageManager` + broadcast receiver | Broadcast |

## Build Variants

The project produces 8 variants: `{mib4,nextgen} × {mock,real} × {debug,release}`.

| Variant | Use Case |
|---|---|
| `mib4MockDebug` | Local dev on AOSP 13 emulator with fake data |
| `mib4RealDebug` | Testing on MIB4 hardware with live signals |
| `nextgenMockDebug` | Local dev on AOSP 15 emulator with fake data |
| `nextgenRealDebug` | Testing on next-gen hardware with live signals |

## Prerequisites

- **Android SDK** with API 36 (compileSdk) installed
- **Java 21** toolchain
- **Gradle 9.3.1** (wrapper included)
- **AOSP platform signing key** — the app requires platform-level permissions

## Getting Started

```bash
# Clone
git clone https://github.com/simonp993/aaos-datacollector-core.git
cd aaos-datacollector-core

# Create local.properties
cp local.properties.template local.properties
# Edit local.properties and set sdk.dir

# Install git hooks
./scripts/install-git-hooks.sh

# Build
./gradlew assembleMib4MockDebug

# Run tests
./gradlew testMib4MockDebugUnitTest

# Check formatting
./gradlew spotlessCheck

# Run static analysis
./gradlew detekt
```

## Permissions

The app requires signature-level permissions (granted via platform key signing):

```xml
<uses-permission android:name="android.permission.MEDIA_CONTENT_CONTROL" />
<uses-permission android:name="android.permission.READ_NETWORK_USAGE_HISTORY" />
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="android.car.permission.CAR_SPEED" />
<uses-permission android:name="android.car.permission.CAR_INFO" />
<uses-permission android:name="android.car.permission.CAR_DRIVING_STATE" />
<!-- ... and more — see AndroidManifest.xml -->
```

## Key References

| Path | Purpose |
|---|---|
| `docs/adrs/` | Architecture Decision Records |
| `docs/rsi-asi-usage-guide.md` | RSI/ASI signal protocol reference |
| `config/detekt/detekt.yml` | Static analysis configuration |
