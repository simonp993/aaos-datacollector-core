# Logcat Viewing Guide

How to view and filter logcat output from the DataCollector service on a connected MIB4/AAOS device.

## Live Dashboard (Python)

A zero-dependency Python script streams `adb logcat` to a browser via SSE for real-time viewing:

```bash
# Start the live logcat dashboard (opens browser automatically)
python3 scripts/telemetry_dashboard.py

# Custom port and device
python3 scripts/telemetry_dashboard.py --port 8765 --serial 172.16.250.248:5555

# Custom logcat filter string
python3 scripts/telemetry_dashboard.py --filter "VehiclePropertyCollector"
```

Opens at **http://127.0.0.1:8765** — no pip dependencies required (stdlib only).

Default filter: `DataCollector:LogTelemetry` (shows telemetry JSON events as they're written).

## Prerequisites

- ADB connected to device: `adb devices` should show `172.16.250.248:5555` (or your emulator)
- Device running the `com.porsche.aaos.platform.telemetry` package

## Quick Commands

### Full DataCollector logcat (filtered by tag)

```bash
adb logcat -s DataCollectorService VehiclePropertyCollector DisplayCollector \
  TouchCollector AppUsageCollector LogTelemetry
```

### All tags from the telemetry package

```bash
adb logcat | grep -i "telemetry\|DataCollector\|VhalProperty\|LogTelemetry"
```

### Only errors and warnings

```bash
adb logcat *:W | grep -i "telemetry\|DataCollector"
```

### Clear and fresh start

```bash
adb logcat -c && adb logcat -s DataCollectorService:V VehiclePropertyCollector:V
```

### Save to file for later analysis

```bash
adb logcat -s DataCollectorService:V > logcat_$(date +%Y%m%d_%H%M%S).txt
```

## Key Log Tags

| Tag | Source | Description |
|-----|--------|-------------|
| `DataCollectorService` | `DataCollectorService.kt` | Service lifecycle, collector start/stop |
| `VehiclePropertyCollector` | `VehiclePropertyCollector.kt` | VHAL observation, batch flush |
| `DisplayCollector` | `DisplayCollector.kt` | Display state changes |
| `TouchCollector` | `TouchCollector.kt` | Touch event batching |
| `AppUsageCollector` | `AppUsageCollector.kt` | App foreground tracking |
| `LogTelemetry` | `LogTelemetry.kt` | JSONL file writes |

## Filtering by Severity

```bash
# Verbose (all)
adb logcat DataCollectorService:V *:S

# Debug and above
adb logcat DataCollectorService:D *:S

# Info and above (recommended for normal monitoring)
adb logcat DataCollectorService:I *:S
```

## Telemetry File Location on Device

The JSONL telemetry files are written to:
```
/data/user/0/com.porsche.aaos.platform.telemetry/files/telemetry-logs/
```

Pull all files:
```bash
adb pull /data/user/0/com.porsche.aaos.platform.telemetry/files/telemetry-logs/ \
  telemetry_analysis/telemetry-logs/mib4-pulled/
```

## Common Patterns

### Watch VHAL flush events (every 60s)
```bash
adb logcat -s VehiclePropertyCollector:D | grep "Flushed"
```

### Watch collector lifecycle
```bash
adb logcat -s DataCollectorService:I | grep -E "Start|Stop|register"
```

### Watch telemetry file rotation
```bash
adb logcat -s LogTelemetry:D | grep -E "Writing|Rotating|flush"
```
