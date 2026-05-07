# Signal Structure Guidelines

Canonical reference for telemetry event structure across all collectors.

---

## TelemetryEvent Envelope

Every event emitted by any collector uses this envelope:

```kotlin
TelemetryEvent(
    signalId = "com.porsche.aaos.datacollector.core.<Name>Collector",
    timestamp = System.currentTimeMillis(),  // epoch millis, auto-set by constructor
    payload = mapOf(
        "actionName" to "<Prefix>_<EventSuffix>",
        "trigger" to "<user|system|heartbeat>",
        "metadata" to mapOf(...)
    )
)
```

- `signalId` — auto-generated from collector name, never set manually.
- `timestamp` — top-level event time, always epoch millis. Do NOT add redundant timestamps at this level.
- `payload["actionName"]` — required (enforced at runtime).
- `payload["trigger"]` — required, see Trigger Taxonomy below.
- `payload["metadata"]` — collector-specific data, structure depends on event type.

---

## Trigger Taxonomy

| Value | Meaning | Example |
|-------|---------|---------|
| `"user"` | Direct user interaction caused this event | Volume change, touch, app switch |
| `"system"` | OS/framework callback, not user-initiated | Network lost, time zone change, VHAL property change |
| `"heartbeat"` | Our own periodic timer fired | 60s poll, batched samples flush |

Rules:
- Cyclic polls we control → `"heartbeat"` (even if the underlying data source is system)
- One-shot initial state collection → `"system"`
- A user action detected via system callback → `"user"` (e.g., volume change comes via AudioManager callback but is user-initiated)

---

## Event Types

### 1. On-Change Events (immediate, individual)

Fired the instant a state transition is detected. Use `"previous"` / `"current"` objects.

```json
{
  "actionName": "Audio_VolumeStateChanged",
  "trigger": "user",
  "metadata": {
    "previous": {"volume": 8, "muted": false, "source": "media"},
    "current": {"volume": 12, "muted": false, "source": "media"}
  }
}
```

Rules:
- `"previous"`: full state snapshot before the change. Set to `null` on first event after startup.
- `"current"`: full state snapshot after the change.
- Both use the same key set (same schema).
- Include `"timestampMillis"` inside previous/current only if the timestamps of prev and current differ meaningfully (e.g., AppLifecycle where you want to know when each activity started).
- Dedup: skip emission if `current == previous` (no actual change).

#### On-change example with timestamps (AppLifecycleCollector):

```json
{
  "actionName": "AppLifecycle_FocusChanged",
  "trigger": "user",
  "metadata": {
    "previous": {"package": "com.android.launcher", "class": "Launcher", "displayId": 0, "displayType": "physical", "timestampMillis": 1715100000000},
    "current": {"package": "com.mapbox.porsche", "class": "MapActivity", "displayId": 0, "displayType": "physical", "timestampMillis": 1715100005000}
  }
}
```

Edge case at startup: `"previous": null` (no prior state known).

### 2. State Announcement Events (immediate, no previous)

For events where "previous" has no meaningful value — the event IS the state.

```json
{
  "actionName": "Connectivity_NetworkLost",
  "trigger": "system",
  "metadata": {
    "network": "wifi"
  }
}
```

Use when:
- The event is a point-in-time fact (touch coordinates, package installed, network lost)
- Tracking previous state would be nonsensical or always null

### 3. Heartbeat Batched Events (periodic flush)

Accumulated samples flushed every 60s. Use `sampleSchema` + positional arrays for compactness.

```json
{
  "actionName": "Battery_StatePolled",
  "trigger": "heartbeat",
  "metadata": {
    "sampleSchema": ["timestampMillis", "level", "charging", "tempTenthsC"],
    "samples": [
      [1715100000000, 87, true, 312],
      [1715100005000, 87, false, 310],
      [1715100010000, 86, false, 308]
    ]
  }
}
```

Rules:
- `"sampleSchema"` — ordered list of field names. Defines the positional meaning of each array element.
- `"samples"` — array of arrays. Each inner array has values in the same order as `sampleSchema`.
- First field should always be `"timestampMillis"` (epoch millis of the sample).
- Flush interval: 60s for all heartbeat collectors.
- Sample rate: collector-specific (5s for sensors/memory/battery, 60s for network/process).
- On-change events during a heartbeat window are NOT emitted separately — they appear as a state transition between consecutive samples in the batch.
- Stagger: each heartbeat collector adds a random initial delay (0–60s) before entering its flush loop to avoid burst load.

#### Stagger implementation pattern:

```kotlin
val staggerDelay = Random.nextLong(0, FLUSH_INTERVAL_MS)
delay(staggerDelay)
while (isActive) {
    flush()
    delay(FLUSH_INTERVAL_MS)
}
```

### 4. One-Shot Events (startup only)

Collected once at service start, never again.

```json
{
  "actionName": "CarInfo_Collected",
  "trigger": "system",
  "metadata": {
    "vin": "WP0ZZZ99ZTS392145",
    "make": "Porsche",
    "model": "Taycan",
    "modelYear": 2025
  }
}
```

No `previous`/`current`, no batching. Flat metadata.

---

## Action Name Convention

Format: `<Prefix>_<EventSuffix>`

- One prefix per collector (e.g., `Audio_`, `Network_`, `VHAL_`).
- Suffix describes what happened (e.g., `VolumeStateChanged`, `FocusChanged`, `StatePolled`).
- Use PascalCase for both prefix and suffix.

**Do NOT mix prefixes within a single collector.** If a collector emits semantically different domains, split it into separate collectors (e.g., `SensorBatteryCollector` → `SensorCollector` + `BatteryCollector`).

---

## Collector Classification

| Collector | Type | prev/current | Batched | Trigger |
|-----------|------|:---:|:---:|---------|
| AudioCollector | on-change + heartbeat | Yes (on-change only) | No | `user` / `heartbeat` |
| AppLifecycleCollector | on-change | Yes | No | `user` |
| TouchInputCollector | state announcement | No | No | `user` |
| ConnectivityCollector | state announcement | No | No | `system` |
| VehiclePropertyCollector | on-change | Yes | No | `system` |
| DriveStateCollector | on-change | Yes | No | `system` |
| MediaPlaybackCollector | on-change | Yes | No | `user` |
| TimeChangeCollector | on-change | Yes | No | `user` / `system` |
| TelephonyCollector | on-change + one-shot | Yes | No | `system` |
| CarInfoCollector | one-shot | No | No | `system` |
| PackageCollector | state announcement + one-shot | No | No | `system` |
| DisplayCollector | on-change | Yes | No | `system` |
| FrameRateCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |
| MemoryCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |
| BatteryCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |
| SensorCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |
| NetworkStatsCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |
| ProcessCollector | heartbeat | No | Yes (schema+arrays) | `heartbeat` |

---

## When to Use Which Pattern

```
Is it a one-time fact at startup?
  → One-shot (type 4)

Does it fire periodically on our timer?
  → Heartbeat batched (type 3), use schema+arrays

Does it fire on external state change?
  → Does "previous state" make sense?
    → Yes: On-change with prev/current (type 1)
    → No:  State announcement (type 2)
```

---

## Timestamp Rules

| Location | Format | When |
|----------|--------|------|
| `TelemetryEvent.timestamp` | epoch millis | Always (auto-set) |
| `metadata.previous.timestampMillis` | epoch millis | Only if timing of prev state matters |
| `metadata.current.timestampMillis` | epoch millis | Only if timing of current state matters |
| `metadata.samples[][0]` (first schema field) | epoch millis | Always in batched events |

- Do NOT use epoch seconds anywhere. Always millis.
- Do NOT include ISO 8601 strings — consumers can format millis themselves.
- Do NOT duplicate the top-level timestamp inside metadata unless the metadata timestamp represents a different moment.

---

## Deduplication Rules

| Collector type | Dedup? | How |
|----------------|:---:|-----|
| On-change | Yes | Skip if `current == previous` |
| State announcement | No | Every event is inherently unique |
| Heartbeat batched | No | Every sample is recorded regardless |
| One-shot | N/A | Fires once |

---

## Checklist for New Collectors

1. Classify: on-change, state announcement, heartbeat, or one-shot?
2. Pick ONE action name prefix matching the collector name.
3. Set trigger correctly (`user`/`system`/`heartbeat`).
4. If on-change: implement `previous`/`current` tracking with dedup.
5. If heartbeat: use `sampleSchema` + `samples` arrays, add stagger delay.
6. Timestamps in millis only.
7. Test on emulator, verify via `adb logcat | grep "DataCollector:LogTelemetry.*<Name>Collector"`.
