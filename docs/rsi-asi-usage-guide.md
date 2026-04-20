# RSI & ASI Usage Guide

Practical guide for using the RSI (Remote Service Interface) and ASI (Application Service Interface) vehicle protocols in the Sport Apps project. For the architectural rationale behind the hybrid protocol decision, see [ADR-007](adrs/ADR-007-asi-vs-rsi-signal-protocol-selection.md).

---

## SDK JARs

Six vendor-provided SDK JARs live in `source/vehicle-connectivity/libs/`. They fall into two categories based on whether the MIB4 system framework provides the classes at runtime.

### System-Provided (stub JARs)

These JARs are **compile-time stubs** — the real implementations live in the system framework (`esofw_android.jar` at `/vendor/framework/`, registered as `de.esolutions.fw.android.platform_library`). Bundling them in the APK causes `RuntimeException("Stub lib does not contain implementation!")`.

| JAR | Contents | Classes |
|-----|----------|---------|
| `fw_rsi_android_stapi.jar` | RSI STAPI API (`IViwiProxy`, `RsiAdminFactory`, `ViwiProxyFactory`) | 32 |
| `fw_util_android_stapi.jar` | Utility types used by RSI | 56 |
| `fw_comm_android_stapi.jar` | Communication STAPI types | 15 |

**Gradle config**: `compileOnly` + `testImplementation` in `vehicle-connectivity/build.gradle.kts`. Never `runtimeOnly` or `implementation`.

### Must-Bundle (ASI JARs)

These JARs are **not** in the system framework (0 classes found via dexdump verification). They must be shipped in the APK.

| JAR | Contents | Classes |
|-----|----------|---------|
| `fw_android_asi_sportchronoservice_client.jar` | `ASISportChronoServiceClientAdapter`, `IServiceCallback` | ~20 |
| `fw_android_asi_sportchronoservice_interface.jar` | `IASISportChronoServiceServiceListener.Stub`, ASI data types | ~30 |
| `fw_comm_android_support.jar` | Communication support (ASI transport layer) | ~9 |

**Gradle config**: `compileOnly` + `testImplementation` in `vehicle-connectivity/build.gradle.kts`. `realRuntimeOnly(files(...))` with explicit paths in `app/build.gradle.kts`. Mock builds do not bundle them.

### Origin

All six JARs are **vendor-provided MIB4 platform SDK artifacts** — pre-built binaries, not compiled from project source. The legacy codebase (`Android_PDEA-MIB4-develop/`) also consumes them as external libraries from `carconnectivitymanager/src/automotive/libs/` and `stapilib/` directories.

---

## Manifest Requirements

### System Framework Declaration

```xml
<application ...>
    <uses-library
        android:name="de.esolutions.fw.android.platform_library"
        android:required="false" />
</application>
```

This loads the ESO system framework at runtime, making the RSI STAPI classes available. `required="false"` allows the app to start even if the framework is absent (e.g., on non-MIB4 devices).

### ESO Permissions

Four permissions are required for RSI and ASI service binding:

```xml
<uses-permission android:name="de.esolutions.android.framework.gateway.BIND_SERVICE_ADMIN_SERVICE" />
<uses-permission android:name="de.esolutions.fw.android.rsi.gateway.BIND_RSI_ADMIN" />
<uses-permission android:name="de.esolutions.fw.comm.permission.CONSUME_ASI_SPORTCHRONOSERVICE_ASISPORTCHRONOSERVICE" />
<uses-permission android:name="de.esolutions.fw.comm.permission.PROVIDE_ASI_SC__IF_SPORTCHRONO" />
```

Without these, binding throws `SecurityException` at runtime.

---

## RSI — Telemetry Observation

RSI is used for **continuous telemetry streams** (temperatures, battery SOC, signal quality). It provides pre-scaled values with automatic unit conversion matching the driver's head-unit preferences.

### API: RSI STAPI (`IViwiProxy`)

The RSI STAPI API uses a proxy-based pattern rather than direct client connections:

```
RsiAdminFactory.createInstance(context)    → IRsiAdmin
ViwiProxyFactory.createInstance(rsiAdmin, serviceUri)  → IViwiProxy
proxy.start()                              → initiates connection
proxy.state                                → observe CONNECTED/DISCONNECTED
proxy.subscribeElement(elementUri, null)    → subscribe to signal updates
IObservable.IObserver<Bundle>              → callback with signal data
proxy.stop()                               → release connection
```

### Signal URL Parsing

CCL signal URLs need to be split into a service URI and element URI:

```
CCL://mmtrCurrentValue
  └── service URI: "CCL://"
  └── element URI: "mmtrCurrentValue"
```

Parse with: `signalUrl.substringBefore("//") + "//"` for service URI.

### Implementation Pattern

```kotlin
// In ExlapRsiSignalSubscriber
fun <T> subscribeToSignal(signalUrl: String): Flow<SignalValue<T>> = callbackFlow {
    val serviceUri = Uri.parse(signalUrl.substringBefore("//") + "//")
    val proxy = ViwiProxyFactory.createInstance(rsiAdmin, serviceUri)

    proxy.start()
    // Observe proxy.state → when CONNECTED:
    //   proxy.subscribeElement(elementUri, null)
    //   Register IObservable.IObserver<Bundle> → parse Bundle → trySend(SignalValue)

    awaitClose { proxy.stop() }
}
```

### Critical: Lazy Initialization

`IRsiAdmin` must be created **lazily** (not in the constructor):

```kotlin
private val rsiAdmin: IRsiAdmin by lazy {
    RsiAdminFactory.createInstance(context)
}
```

Eager initialization crashes when the system framework is unavailable (DI graph construction, tests).

### Quality Mapping

RSI provides signal quality metadata. Map to the project's `SignalQuality` enum:
- Valid data → `SignalQuality.VALID`
- Invalid/error → `SignalQuality.INVALID`
- Unavailable/no-data → `SignalQuality.NOT_AVAILABLE`

### Emulator Limitation

On the AAOS emulator, `IViwiProxy` never reaches `CONNECTED` state because the CCL backend is not available. This means:
- `devReal` builds show a blank/loading MMTR screen on emulator — **this is expected**
- Use `devMock` flavor for emulator development
- On real MIB4 hardware with CCL backend, RSI connections work normally

---

## ASI — Commands & Service Binding

ASI is used for **command execution** (e.g., starting preconditioning) and **state machine inputs** requiring low-latency service binding.

### API: ASISportChronoServiceClientAdapter

```
AsiAdmin.start(context)                    → IAsiAdmin (singleton)
ASISportChronoServiceClientAdapter(1, IServiceCallback)  → adapter
adapter.connectService(listener, asiAdmin) → bind to service
adapter.api                                → service API for commands
adapter.disconnectService()                → unbind
```

### Critical: 2-Argument connectService

**Always use the 2-argument form:**

```kotlin
adapter.connectService(serviceListener, asiAdmin)
```

The 1-argument `connectService(listener)` internally passes `null` for `asiAdmin`, causing an NPE. This is a known SDK issue.

### Listener Stub

`connectService` requires a non-null `IASISportChronoServiceServiceListener.Stub` with all 23 callback methods implemented. Use no-op implementations:

```kotlin
private val serviceListener by lazy {
    object : IASISportChronoServiceServiceListener.Stub() {
        override fun cycleSetDamperSetup(...) = Unit
        override fun cycleSetERaceConditioningState(...) = Unit
        // ... all 23 methods as no-op
    }
}
```

### Critical: Lazy Initialization

Both `asiAdmin` and `serviceListener` must be **lazy**:

```kotlin
private val asiAdmin: IAsiAdmin by lazy { AsiAdmin.start(context) }
private val serviceListener by lazy { object : IASISportChronoServiceServiceListener.Stub() { ... } }
```

The `Stub` extends `android.os.Binder` — eager construction crashes in JVM tests and when the framework is unavailable.

### SecurityException Handling

Wrap `connectService()` in a try-catch:

```kotlin
fun connect() {
    try {
        adapter.connectService(serviceListener, asiAdmin)
    } catch (e: SecurityException) {
        logger.error("ASI binding denied: ${e.message}")
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
```

This prevents crashes on devices where ESO permissions are not granted.

### Command Execution

Map `AsiCommand.operationId` to adapter API calls:

```kotlin
when (command.operationId) {
    "setRaceConditioning" -> connector.adapter.api.setRaceConditioning(enabled)
}
```

---

## Build & Test Configuration

### Convention Plugin

The `datacollector.android-library` convention plugin requires:

```kotlin
android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
```

This allows JVM tests to instantiate `android.os.Binder` subclasses (like `IASISportChronoServiceServiceListener.Stub`) without crashing.

### FakeRsiSignalSubscriber

The fake must use `MutableSharedFlow(replay = 1, extraBufferCapacity = 1)`. Without `replay = 1`, values seeded via `runBlocking` during DI initialization are lost before the first subscriber arrives.

### ProGuard/R8

Add consumer rules in `vehicle-connectivity/consumer-rules.pro` to keep RSI and ASI callback interfaces that are invoked by the platform SDK at runtime.

---

## DI Wiring

Per [ADR-010](adrs/ADR-010-flavor-based-di-binding-strategy.md), DI modules live in `app/` flavor source sets:

| Flavor | Module | Provides |
|--------|--------|----------|
| `mock` | `MockVehicleConnectivityModule` | `FakeRsiSignalSubscriber`, `FakeAsiServiceConnector` (CONNECTED), `FakeAsiCommandExecutor` + MMTR signal seeding |
| `real` | `RealVehicleConnectivityModule` | `ExlapRsiSignalSubscriber(context)`, `SportChronoAsiServiceConnector(context)` with eager `connect()`, `SportChronoAsiCommandExecutor` |

Both real implementations receive `@ApplicationContext` via constructor injection.

---

## Quick Reference: Adding a New Signal

1. **Determine protocol** per [ADR-007](adrs/ADR-007-asi-vs-rsi-signal-protocol-selection.md) decision criteria (command → ASI, telemetry → RSI)
2. **RSI signal**: Add CCL URL to the feature's repository, subscribe via `RsiSignalSubscriber.subscribeToSignal<T>(url)`
3. **ASI command**: Add operation mapping in the relevant `AsiCommandExecutor`, ensure `AsiServiceConnector` is connected
4. **Mock seeding**: Add default value in `MockVehicleConnectivityModule` `@Provides` method
5. **Document**: Include protocol table in the feature's OpenSpec design artifact
