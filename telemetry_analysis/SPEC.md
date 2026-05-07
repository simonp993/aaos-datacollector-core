# Telemetry Analysis Pipeline — Functional Specification

## Overview

One-click pipeline that ingests 1-N `.jsonl` telemetry files and generates a standalone interactive HTML report with graphs, correlations, and session summaries.

---

## Architecture Decision: Pure Python Script + Plotly HTML

**Why NOT Jupyter Notebook:**
- JSON cell outputs bloat the file (100KB+ per graph), making git diffs useless
- AI tools struggle with notebook JSON format
- Requires `jupyter` + `nbconvert` runtime dependency
- Not truly "one-click" — needs kernel setup

**Chosen approach:** `python3 analysis/generate_report.py`
- Single Python script, no notebook
- Outputs a self-contained `report.html` (Plotly JS embedded)
- Zero server dependencies — open in any browser
- Easy to version control (pure `.py`)
- AI-friendly for iteration

---

## Usage

```bash
# Generate report from all .jsonl files in a directory
python3 analysis/generate_report.py telemetry-logs/telemetry-logs/

# Or specify individual files
python3 analysis/generate_report.py session1.jsonl session2.jsonl

# Output: analysis/output/report_2026-05-07_143000.html
```

---

## Dependencies

```
pandas>=2.0
plotly>=5.18
```

No jupyter, no node, no build step.

---

## Pipeline Steps

1. **Ingest** — Read all `.jsonl` files, parse each line as JSON, tag with session ID (filename)
2. **Normalize** — Convert to pandas DataFrames per collector type, with shared timestamp index
3. **Analyze** — Compute derived metrics (durations, rates, deltas)
4. **Visualize** — Generate Plotly figures per section
5. **Export** — Combine into single HTML with navigation sidebar

---

## Report Sections & Graphs

### 1. Session Overview
| Graph | Type | Data Source |
|-------|------|-------------|
| Session timeline | Horizontal bars | All events (first/last timestamp per file) |
| Event density | Heatmap (time × collector) | All events |
| Event count by collector | Horizontal bar | All events |

### 2. App Usage (AppLifecycleCollector → `App_FocusChanged`)
| Graph | Type | Data Source |
|-------|------|-------------|
| App flow diagram | Sankey (from → to) | metadata.previous / metadata.current packageName |
| Time-in-app | Horizontal bar (total seconds) | Duration between focus changes |
| App open frequency | Bar chart | Count per packageName |
| App session durations | Box plot per app | Individual focus durations |
| App usage timeline | Gantt/swim-lane | Foreground app over time |

### 3. Touch Interaction (TouchInputCollector → `Touch_Down`, `Touch_Up`)
| Graph | Type | Data Source |
|-------|------|-------------|
| Touch heatmap | 2D density heatmap, **one per display** | metadata.x, metadata.y, metadata.displayId |
| Touch rate over time | Line chart (touches/min) | Binned count |
| Touch-to-app correlation | Grouped bars | Cross-reference with App_FocusChanged |
| Touch duration distribution | Histogram | Touch_Down → Touch_Up delta |

**Heatmap details:**
- Render a separate heatmap for each unique `metadata.displayId` (0 = Center, 1 = Instrument, 2 = Passenger, 3 = Rear).
- Use Plotly `density_heatmap` with axes matching the display resolution.
- Label each subplot with the display name and total touch count.

### 4. Audio & Volume (AudioCollector → `Audio_Snapshot`, `Audio_StateChanged`)
| Graph | Type | Data Source |
|-------|------|-------------|
| Volume levels timeline | Step line per group | carVolumeGroups values (parsed "N/M" → ratio) |
| Volume change frequency | Bar per group | Count of StateChanged events |
| Volume vs vehicle speed | Dual-axis line | Cross-ref VehiclePropertyCollector |

### 5. Memory & Performance (MemoryCollector → `Memory_Usage`, FrameRateCollector → `Display_FrameRate`)
| Graph | Type | Data Source |
|-------|------|-------------|
| Available memory timeline | Line chart | metadata.samples[][availMb] (expand schema) |
| Frame rate timeline | Line chart | metadata.samples[][fps] |
| Dropped frames over time | Bar chart | metadata.samples[][dropped] |
| Memory vs FPS correlation | Scatter plot | Aligned timestamps |
| Low-memory events | Threshold markers | availMb < threshold |

### 6. Connectivity & Network (ConnectivityCollector, NetworkStatsCollector)
| Graph | Type | Data Source |
|-------|------|-------------|
| Signal strength timeline | Line chart | metadata.signalStrength / rssi |
| Network type over time | State band chart | metadata.networkType |
| Data usage per app | Stacked bar (tx/rx) | Network_PerAppTraffic |
| Total traffic timeline | Cumulative line | Network_WifiTotal / Network_MobileTotal |

### 7. Vehicle State (VehiclePropertyCollector → `VHAL_ValuesChanged`, PowerStateCollector)
| Graph | Type | Data Source |
|-------|------|-------------|
| Speed profile | Line chart | PERF_VEHICLE_SPEED from changes array |
| Ignition state timeline | State band | IGNITION_STATE |
| HVAC temperature timeline | Multi-line | HVAC_TEMPERATURE_SET / CURRENT |
| Gear selection timeline | State band | GEAR_SELECTION |
| Power lifecycle events | Event markers on timeline | Power_Boot / Power_StateChanged |
| Parking brake timeline | Binary band | PARKING_BRAKE_ON |

### 8. Storage & Battery (StorageCollector, SensorBatteryCollector)
| Graph | Type | Data Source |
|-------|------|-------------|
| Battery level timeline | Line chart | metadata.batteryLevel (from samples) |
| Storage usage trend | Line chart | metadata.usagePercent |
| Battery drain rate | Derived line | Delta per interval |

### 9. Media (MediaPlaybackCollector → `Media_PlaybackChanged`, `Media_TrackChanged`)
| Graph | Type | Data Source |
|-------|------|-------------|
| Playback state timeline | State band | PLAYING / PAUSED / STOPPED |
| Source distribution | Pie chart | metadata.source or packageName |
| Track skip frequency | Histogram | TrackChanged events/minute |

---

## Cross-Collector Correlations (Section 10)

| Correlation | X-axis | Y-axis | Graph Type |
|-------------|--------|--------|------------|
| Speed → Volume | PERF_VEHICLE_SPEED | Volume level | Scatter + trend line |
| App switch → Memory spike | App_FocusChanged time | Memory delta | Event + line overlay |
| Touch rate → Frame drops | Touches/min | Dropped frames | Dual-axis timeline |
| Signal loss → App errors | Connectivity gaps | App crash/focus loss | Aligned timeline |
| Boot → First interaction | Power_Boot timestamp | First Touch_Down | Bar (latency) |
| Suspend duration | Power_StateChanged pairs | Duration histogram | Histogram |

---

## HTML Report Structure

```html
<html>
  <head><!-- Plotly.js CDN or embedded --></head>
  <body>
    <nav><!-- Sidebar with section links --></nav>
    <main>
      <section id="overview">...</section>
      <section id="app-usage">...</section>
      <section id="touch">...</section>
      <section id="audio">...</section>
      <section id="performance">...</section>
      <section id="network">...</section>
      <section id="vehicle">...</section>
      <section id="storage-battery">...</section>
      <section id="media">...</section>
      <section id="correlations">...</section>
    </main>
  </body>
</html>
```

---

## Implementation Notes

- Use `plotly.io.to_html(fig, full_html=False)` per graph, concatenate into template
- Parse VHAL batch schema dynamically: read `sampleSchema` → use as column headers
- Expand `samples` arrays (Memory, FrameRate) into proper DataFrames using their `sampleSchema`
- Handle missing collectors gracefully (skip section if no data)
- Support multiple sessions: show session selector or overlay with color coding
- Timestamp handling: all events use `timestamp` (epochMillis) → convert to datetime

---

## Future Extensions

- [ ] Upload to a web server for team sharing
- [ ] Compare two sessions side-by-side (A/B test drives)
- [ ] Anomaly detection (z-score on memory, frame drops, battery drain)
- [ ] Backend API integration (replace JSONL file reader with REST client)
- [ ] Real-time streaming mode (WebSocket from device → live dashboard)

---

**Last Updated**: 2026-05-07
