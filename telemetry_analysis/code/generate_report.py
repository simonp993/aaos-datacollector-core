#!/usr/bin/env python3
"""
Telemetry Analysis Pipeline — generates a standalone interactive HTML report
from AAOS DataCollector JSONL telemetry files.

Usage:
    python3 generate_report.py <path_to_jsonl_dir_or_files...>
    # Output: telemetry_analysis/output/report_<timestamp>.html
"""

import json
import sys
import os
from datetime import datetime
from pathlib import Path
from collections import defaultdict

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from plotly.subplots import make_subplots
import plotly.io as pio

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DISPLAY_NAMES = {
    0: "Center Screen",
    1: "Instrument Cluster",
    2: "Passenger Screen",
    3: "Rear Passenger",
}

DISPLAY_RESOLUTIONS = {
    0: (1920, 720),
    1: (1920, 1080),
    2: (1920, 720),
    3: (1280, 768),
}

OUTPUT_DIR = Path(__file__).parent.parent / "output"


def _figs_to_html(figs: list) -> str:
    """Convert list of plotly figures to HTML fragments (no plotly.js included)."""
    return "\n".join(pio.to_html(f, full_html=False, include_plotlyjs=False) for f in figs)

# ---------------------------------------------------------------------------
# Ingest
# ---------------------------------------------------------------------------


def ingest(paths: list[str]) -> list[dict]:
    """Read all JSONL files, return list of parsed events."""
    events = []
    for path in paths:
        p = Path(path)
        if p.is_dir():
            files = sorted(p.glob("*.jsonl"))
        else:
            files = [p]
        for f in files:
            session_id = f.stem
            with open(f) as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        event = json.loads(line)
                        event["_session"] = session_id
                        events.append(event)
                    except json.JSONDecodeError:
                        continue
    return events


# ---------------------------------------------------------------------------
# Normalize — build DataFrames per collector type
# ---------------------------------------------------------------------------


def normalize(events: list[dict]) -> dict[str, pd.DataFrame]:
    """Group events by actionName and return DataFrames."""
    grouped = defaultdict(list)
    for e in events:
        payload = e.get("payload", {})
        action = payload.get("actionName", "unknown")
        meta = payload.get("metadata", {})
        row = {
            "timestamp": e.get("timestamp"),
            "session": e.get("_session"),
            "signalId": e.get("signalId", ""),
            "trigger": payload.get("trigger", ""),
            "actionName": action,
        }
        row.update(_flatten_metadata(meta))
        grouped[action].append(row)

    dfs = {}
    for action, rows in grouped.items():
        df = pd.DataFrame(rows)
        if "timestamp" in df.columns:
            df["datetime"] = pd.to_datetime(df["timestamp"], unit="ms")
            df = df.sort_values("datetime").reset_index(drop=True)
        dfs[action] = df
    return dfs


def _flatten_metadata(meta: dict, prefix: str = "") -> dict:
    """Flatten metadata but keep samples/changes/apps as-is."""
    result = {}
    for k, v in meta.items():
        key = f"{prefix}{k}" if prefix else k
        if k in ("samples", "changes", "sampleSchema", "apps", "packages"):
            result[key] = v
        elif isinstance(v, dict):
            result.update(_flatten_metadata(v, f"{key}."))
        else:
            result[key] = v
    return result


def expand_samples(df: pd.DataFrame) -> pd.DataFrame:
    """Expand rows with sampleSchema + samples into individual rows."""
    if df.empty or "sampleSchema" not in df.columns or "samples" not in df.columns:
        return df
    expanded_rows = []
    for _, row in df.iterrows():
        schema = row.get("sampleSchema")
        samples = row.get("samples")
        if not schema or not samples:
            continue
        for sample in samples:
            r = dict(zip(schema, sample))
            r["session"] = row.get("session")
            expanded_rows.append(r)
    if not expanded_rows:
        return df
    result = pd.DataFrame(expanded_rows)
    if "timestampMillis" in result.columns:
        result["datetime"] = pd.to_datetime(result["timestampMillis"], unit="ms")
        result = result.sort_values("datetime").reset_index(drop=True)
    return result


# ---------------------------------------------------------------------------
# Section Builders
# ---------------------------------------------------------------------------


def build_overview(events: list[dict], dfs: dict) -> str:
    """Section 1: Session overview."""
    figs = []

    # Event count by collector
    collector_counts = defaultdict(int)
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        collector_counts[sig] += 1
    df_counts = pd.DataFrame(
        sorted(collector_counts.items(), key=lambda x: x[1]),
        columns=["Collector", "Count"],
    )
    fig = px.bar(
        df_counts,
        x="Count",
        y="Collector",
        orientation="h",
        title="Event Count by Collector",
        color="Count",
        color_continuous_scale="Viridis",
    )
    fig.update_layout(height=400, showlegend=False)
    figs.append(fig)

    # Event density heatmap (time × collector)
    all_rows = []
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        ts = e.get("timestamp")
        if ts:
            all_rows.append({"collector": sig, "datetime": pd.to_datetime(ts, unit="ms")})
    if all_rows:
        df_all = pd.DataFrame(all_rows)
        df_all["time_bin"] = df_all["datetime"].dt.floor("30s")
        density = df_all.groupby(["time_bin", "collector"]).size().reset_index(name="count")
        fig = px.density_heatmap(
            density,
            x="time_bin",
            y="collector",
            z="count",
            title="Event Density Over Time (30s bins)",
            color_continuous_scale="Hot",
        )
        fig.update_layout(height=500)
        figs.append(fig)

    # Session timeline
    session_spans = []
    for e in events:
        session_spans.append({"session": e.get("_session"), "timestamp": e.get("timestamp")})
    df_sessions = pd.DataFrame(session_spans)
    if not df_sessions.empty:
        session_summary = df_sessions.groupby("session").agg(
            start=("timestamp", "min"), end=("timestamp", "max")
        ).reset_index()
        session_summary["start_dt"] = pd.to_datetime(session_summary["start"], unit="ms")
        session_summary["end_dt"] = pd.to_datetime(session_summary["end"], unit="ms")
        session_summary["duration_min"] = (session_summary["end"] - session_summary["start"]) / 60000
        fig = px.timeline(
            session_summary,
            x_start="start_dt",
            x_end="end_dt",
            y="session",
            title="Session Timelines",
            color="duration_min",
            labels={"duration_min": "Duration (min)"},
        )
        fig.update_layout(height=300)
        figs.append(fig)

    # Summary stats
    total_events = len(events)
    total_duration_ms = max(e["timestamp"] for e in events) - min(e["timestamp"] for e in events)
    total_duration_min = total_duration_ms / 60000
    stats_html = f"""
    <div class="stats-grid">
        <div class="stat-card"><h3>{total_events}</h3><p>Total Events</p></div>
        <div class="stat-card"><h3>{total_duration_min:.1f} min</h3><p>Total Recording</p></div>
        <div class="stat-card"><h3>{len(collector_counts)}</h3><p>Active Collectors</p></div>
        <div class="stat-card"><h3>{len(set(e.get('_session') for e in events))}</h3><p>Sessions</p></div>
    </div>
    """

    return stats_html + _figs_to_html(figs)


def build_app_usage(dfs: dict) -> str:
    """Section 2: App usage analysis."""
    df = dfs.get("App_FocusChanged")
    if df is None or df.empty:
        return "<p>No App_FocusChanged data available.</p>"

    figs = []

    # Extract package names
    df["current_pkg"] = df.apply(
        lambda r: str(r.get("current.package", r.get("current.packageName", "")) or ""), axis=1
    )
    df["previous_pkg"] = df.apply(
        lambda r: str(r.get("previous.package", r.get("previous.packageName", "")) or ""), axis=1
    )
    df["display_id"] = df.get("current.displayId", df.get("displayId", 0))

    # Filter to physical displays only (0-3)
    df_physical = df[df.get("current.displayId", pd.Series([0] * len(df))).fillna(0).astype(int) < 10]

    # App open frequency
    pkg_counts = df_physical["current_pkg"].value_counts().reset_index()
    pkg_counts.columns = ["Package", "Focus Count"]
    # Shorten package names for display
    pkg_counts["Short"] = pkg_counts["Package"].apply(lambda x: x.split(".")[-1] if x else "None")
    fig = px.bar(
        pkg_counts.head(15),
        x="Focus Count",
        y="Short",
        orientation="h",
        title="App Focus Frequency (Top 15)",
        hover_data=["Package"],
    )
    fig.update_layout(height=400)
    figs.append(fig)

    # Time-in-app calculation
    df_sorted = df_physical.sort_values("datetime").reset_index(drop=True)
    durations = []
    for i in range(len(df_sorted) - 1):
        pkg = str(df_sorted.iloc[i]["current_pkg"]) if pd.notna(df_sorted.iloc[i]["current_pkg"]) else ""
        dur = (df_sorted.iloc[i + 1]["timestamp"] - df_sorted.iloc[i]["timestamp"]) / 1000
        if dur < 3600:  # cap at 1 hour to ignore idle gaps
            durations.append({"package": pkg, "duration_s": dur})
    if durations:
        df_dur = pd.DataFrame(durations)
        app_time = df_dur.groupby("package")["duration_s"].sum().reset_index()
        app_time["Short"] = app_time["package"].apply(lambda x: x.split(".")[-1] if x else "None")
        app_time = app_time.sort_values("duration_s")
        fig = px.bar(
            app_time.tail(15),
            x="duration_s",
            y="Short",
            orientation="h",
            title="Time in App (seconds, Top 15)",
            hover_data=["package"],
        )
        fig.update_layout(height=400)
        figs.append(fig)

    # App usage timeline (Gantt)
    if durations:
        timeline_data = []
        for i in range(len(df_sorted) - 1):
            pkg = str(df_sorted.iloc[i]["current_pkg"]) if pd.notna(df_sorted.iloc[i]["current_pkg"]) else ""
            start = df_sorted.iloc[i]["datetime"]
            end = df_sorted.iloc[i + 1]["datetime"]
            short = pkg.split(".")[-1] if pkg else "None"
            timeline_data.append({"App": short, "Start": start, "End": end, "Package": pkg})
        df_timeline = pd.DataFrame(timeline_data)
        # Filter to reasonable durations
        df_timeline["dur_s"] = (df_timeline["End"] - df_timeline["Start"]).dt.total_seconds()
        df_timeline = df_timeline[df_timeline["dur_s"] < 600]
        if not df_timeline.empty:
            fig = px.timeline(
                df_timeline,
                x_start="Start",
                x_end="End",
                y="App",
                title="App Usage Timeline",
                color="App",
            )
            fig.update_layout(height=400, showlegend=False)
            figs.append(fig)

    # Sankey: app transitions
    transitions = df_physical[["previous_pkg", "current_pkg"]].dropna()
    transitions = transitions[transitions["previous_pkg"] != ""]
    if len(transitions) > 0:
        trans_counts = transitions.groupby(["previous_pkg", "current_pkg"]).size().reset_index(name="count")
        trans_counts = trans_counts.sort_values("count", ascending=False).head(20)
        all_nodes = list(set(trans_counts["previous_pkg"].tolist() + trans_counts["current_pkg"].tolist()))
        all_nodes_short = [n.split(".")[-1] for n in all_nodes]
        node_map = {n: i for i, n in enumerate(all_nodes)}
        fig = go.Figure(go.Sankey(
            node=dict(label=all_nodes_short, pad=15, thickness=20),
            link=dict(
                source=[node_map[r["previous_pkg"]] for _, r in trans_counts.iterrows()],
                target=[node_map[r["current_pkg"]] for _, r in trans_counts.iterrows()],
                value=trans_counts["count"].tolist(),
            ),
        ))
        fig.update_layout(title="App Transition Flow (Top 20)", height=500)
        figs.append(fig)

    return _figs_to_html(figs)


def build_touch(dfs: dict) -> str:
    """Section 3: Touch interaction analysis with per-display heatmaps."""
    figs = []
    touch_actions = ["Touch_Down", "Touch_Up", "Touch_Swipe"]
    touch_dfs = [dfs[a] for a in touch_actions if a in dfs and not dfs[a].empty]

    if not touch_dfs:
        return "<p>No touch data available.</p>"

    df_down = dfs.get("Touch_Down", pd.DataFrame())

    # Per-display heatmaps
    if not df_down.empty and "displayId" in df_down.columns:
        display_ids = sorted(df_down["displayId"].dropna().unique())
        for did in display_ids:
            did_int = int(did)
            df_disp = df_down[df_down["displayId"] == did_int]
            if df_disp.empty:
                continue
            name = DISPLAY_NAMES.get(did_int, f"Display {did_int}")
            res = DISPLAY_RESOLUTIONS.get(did_int, (1920, 720))
            fig = px.density_heatmap(
                df_disp,
                x="x",
                y="y",
                title=f"Touch Heatmap — {name} (n={len(df_disp)})",
                nbinsx=48,
                nbinsy=int(48 * res[1] / res[0]),
                color_continuous_scale="Hot",
            )
            fig.update_xaxes(range=[0, res[0]], title="X")
            fig.update_yaxes(range=[res[1], 0], title="Y")  # Invert Y (screen coords)
            fig.update_layout(height=400, width=700)
            figs.append(fig)

    # Touch rate over time
    all_touch = pd.concat(touch_dfs, ignore_index=True) if touch_dfs else pd.DataFrame()
    if not all_touch.empty and "datetime" in all_touch.columns:
        all_touch["time_bin"] = all_touch["datetime"].dt.floor("30s")
        rate = all_touch.groupby("time_bin").size().reset_index(name="touches")
        rate["touches_per_min"] = rate["touches"] * 2  # 30s bins → per min
        fig = px.line(
            rate,
            x="time_bin",
            y="touches_per_min",
            title="Touch Rate Over Time (per minute)",
            markers=True,
        )
        fig.update_layout(height=300)
        figs.append(fig)

    # Touch duration distribution (Down → Up pairing)
    df_up = dfs.get("Touch_Up", pd.DataFrame())
    if not df_down.empty and not df_up.empty:
        # Simple: pair consecutive down/up by timestamp
        downs = df_down["timestamp"].sort_values().values
        ups = df_up["timestamp"].sort_values().values
        durations = []
        up_idx = 0
        for d in downs:
            while up_idx < len(ups) and ups[up_idx] < d:
                up_idx += 1
            if up_idx < len(ups):
                dur = ups[up_idx] - d
                if 0 < dur < 5000:  # reasonable touch duration < 5s
                    durations.append(dur)
                    up_idx += 1
        if durations:
            fig = px.histogram(
                x=durations,
                nbins=30,
                title="Touch Duration Distribution (ms)",
                labels={"x": "Duration (ms)", "y": "Count"},
            )
            fig.update_layout(height=300)
            figs.append(fig)

    return _figs_to_html(figs)


def build_audio(dfs: dict) -> str:
    """Section 4: Audio & Volume."""
    figs = []

    df_snap = dfs.get("Audio_Snapshot", pd.DataFrame())
    df_change = dfs.get("Audio_StateChanged", pd.DataFrame())

    # Parse volume groups from snapshots
    if not df_snap.empty:
        vol_rows = []
        for _, row in df_snap.iterrows():
            dt = row.get("datetime")
            # Find carVolumeGroups columns
            for col in row.index:
                if "carVolumeGroups" in col and "." in col:
                    group_name = col.split(".")[-1] if "." in col else col
                    val = row[col]
                    if isinstance(val, str) and "/" in val:
                        num, den = val.split("/")
                        try:
                            ratio = int(num) / int(den)
                            vol_rows.append({
                                "datetime": dt,
                                "group": group_name,
                                "level": int(num),
                                "max": int(den),
                                "ratio": ratio,
                            })
                        except ValueError:
                            pass
        if vol_rows:
            df_vol = pd.DataFrame(vol_rows)
            fig = px.line(
                df_vol,
                x="datetime",
                y="level",
                color="group",
                title="Volume Levels Over Time",
                line_shape="hv",
                markers=True,
            )
            fig.update_layout(height=400)
            figs.append(fig)

    # Volume change frequency from StateChanged
    if not df_change.empty:
        change_rows = []
        for _, row in df_change.iterrows():
            dt = row.get("datetime")
            # Look for current.carVolumeGroups changes
            for col in row.index:
                if "current" in col and "carVolumeGroups" in col:
                    group_name = col.replace("current.", "").replace("carVolumeGroups.", "")
                    if row[col] is not None and not pd.isna(row[col]):
                        change_rows.append({"datetime": dt, "group": group_name})
        if change_rows:
            df_changes = pd.DataFrame(change_rows)
            counts = df_changes["group"].value_counts().reset_index()
            counts.columns = ["Group", "Changes"]
            fig = px.bar(
                counts,
                x="Changes",
                y="Group",
                orientation="h",
                title="Volume Change Frequency by Group",
            )
            fig.update_layout(height=300)
            figs.append(fig)

    if not figs:
        return "<p>No audio data available.</p>"
    return _figs_to_html(figs)


def build_performance(dfs: dict) -> str:
    """Section 5: Memory & Frame Rate."""
    figs = []

    # Memory
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_mem.empty:
        mem_expanded = expand_samples(df_mem)
        if "availMb" in mem_expanded.columns and "datetime" in mem_expanded.columns:
            fig = px.line(
                mem_expanded,
                x="datetime",
                y="availMb",
                title="Available Memory Over Time (MB)",
                markers=True,
            )
            fig.update_layout(height=350)
            figs.append(fig)

    # Frame Rate
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    if not df_fps.empty:
        fps_expanded = expand_samples(df_fps)
        if "fps" in fps_expanded.columns and "datetime" in fps_expanded.columns:
            fig = px.line(
                fps_expanded,
                x="datetime",
                y="fps",
                title="Frame Rate Over Time (FPS)",
                markers=True,
            )
            fig.add_hline(y=60, line_dash="dash", line_color="green", annotation_text="60fps target")
            fig.update_layout(height=350)
            figs.append(fig)

        if "dropped" in fps_expanded.columns:
            fig = px.bar(
                fps_expanded,
                x="datetime",
                y="dropped",
                title="Dropped Frames Over Time",
            )
            fig.update_layout(height=300)
            figs.append(fig)

    # Correlation: Memory vs FPS
    if not df_mem.empty and not df_fps.empty:
        mem_exp = expand_samples(df_mem)
        fps_exp = expand_samples(df_fps)
        if "availMb" in mem_exp.columns and "fps" in fps_exp.columns:
            # Merge on nearest timestamp
            mem_exp["ts"] = mem_exp.get("timestampMillis", 0)
            fps_exp["ts"] = fps_exp.get("timestampMillis", 0)
            merged = pd.merge_asof(
                mem_exp.sort_values("ts")[["ts", "availMb", "datetime"]],
                fps_exp.sort_values("ts")[["ts", "fps"]],
                on="ts",
                direction="nearest",
                tolerance=5000,
            )
            merged = merged.dropna()
            if len(merged) > 2:
                fig = px.scatter(
                    merged,
                    x="availMb",
                    y="fps",
                    title="Memory vs FPS Correlation",
                )
                fig.update_layout(height=350)
                figs.append(fig)

    if not figs:
        return "<p>No performance data available.</p>"
    return _figs_to_html(figs)


def build_network(dfs: dict) -> str:
    """Section 6: Connectivity & Network."""
    figs = []

    # Signal strength
    df_signal = dfs.get("Connectivity_SignalStrength", pd.DataFrame())
    if not df_signal.empty:
        sig_expanded = expand_samples(df_signal)
        if "signalStrengthDbm" in sig_expanded.columns and "datetime" in sig_expanded.columns:
            fig = px.line(
                sig_expanded,
                x="datetime",
                y="signalStrengthDbm",
                title="WiFi Signal Strength (dBm)",
                markers=True,
            )
            fig.update_layout(height=350)
            figs.append(fig)

    # Total traffic over time
    df_wifi = dfs.get("Network_WifiTotal", pd.DataFrame())
    if not df_wifi.empty and "datetime" in df_wifi.columns:
        fig = go.Figure()
        if "rxBytes" in df_wifi.columns:
            fig.add_trace(go.Scatter(
                x=df_wifi["datetime"],
                y=df_wifi["rxBytes"] / 1024,
                mode="lines+markers",
                name="RX (KB)",
            ))
        if "txBytes" in df_wifi.columns:
            fig.add_trace(go.Scatter(
                x=df_wifi["datetime"],
                y=df_wifi["txBytes"] / 1024,
                mode="lines+markers",
                name="TX (KB)",
            ))
        fig.update_layout(title="WiFi Traffic Over Time", height=350, yaxis_title="KB")
        figs.append(fig)

    # Per-app traffic
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    if not df_perapp.empty and "apps" in df_perapp.columns:
        # Take the latest snapshot
        latest = df_perapp.iloc[-1]
        apps_data = latest.get("apps", [])
        if apps_data:
            app_traffic = []
            for app in apps_data:
                if isinstance(app, dict):
                    pkgs = app.get("packages", [])
                    label = pkgs[0] if pkgs else f"uid:{app.get('uid')}"
                    label = label.split(".")[-1] if "." in label else label
                    rx = app.get("rxBytes", 0) / 1024
                    tx = app.get("txBytes", 0) / 1024
                    if rx + tx > 0:
                        app_traffic.append({"App": label, "RX (KB)": rx, "TX (KB)": tx})
            if app_traffic:
                df_traffic = pd.DataFrame(app_traffic).sort_values("RX (KB)", ascending=True).tail(10)
                fig = go.Figure()
                fig.add_trace(go.Bar(y=df_traffic["App"], x=df_traffic["RX (KB)"], name="RX", orientation="h"))
                fig.add_trace(go.Bar(y=df_traffic["App"], x=df_traffic["TX (KB)"], name="TX", orientation="h"))
                fig.update_layout(
                    title="Per-App Network Traffic (Top 10, KB)",
                    barmode="group",
                    height=400,
                )
                figs.append(fig)

    if not figs:
        return "<p>No network data available.</p>"
    return _figs_to_html(figs)


def build_vehicle(dfs: dict) -> str:
    """Section 7: Vehicle state from VHAL."""
    figs = []

    df_vhal = dfs.get("VHAL_ValuesChanged", pd.DataFrame())
    if not df_vhal.empty and "changes" in df_vhal.columns:
        # Expand changes into rows
        vhal_rows = []
        for _, row in df_vhal.iterrows():
            changes = row.get("changes", [])
            schema = row.get("sampleSchema", ["timestampMillis", "property", "previous", "current"])
            if changes:
                for change in changes:
                    r = dict(zip(schema, change))
                    vhal_rows.append(r)
        if vhal_rows:
            df_props = pd.DataFrame(vhal_rows)
            if "timestampMillis" in df_props.columns:
                df_props["datetime"] = pd.to_datetime(df_props["timestampMillis"], unit="ms")

            # Speed profile
            df_speed = df_props[df_props["property"] == "PERF_VEHICLE_SPEED"].copy()
            if not df_speed.empty:
                df_speed["current"] = pd.to_numeric(df_speed["current"], errors="coerce")
                fig = px.line(
                    df_speed,
                    x="datetime",
                    y="current",
                    title="Vehicle Speed (PERF_VEHICLE_SPEED)",
                    markers=True,
                )
                fig.update_layout(height=350, yaxis_title="Value")
                figs.append(fig)

            # Gear selection
            df_gear = df_props[df_props["property"] == "GEAR_SELECTION"].copy()
            if not df_gear.empty:
                gear_map = {1: "P", 2: "R", 4: "N", 8: "D"}
                df_gear["gear_label"] = df_gear["current"].apply(
                    lambda x: gear_map.get(x, str(x)) if x is not None else "?"
                )
                fig = px.scatter(
                    df_gear,
                    x="datetime",
                    y="gear_label",
                    title="Gear Selection",
                    text="gear_label",
                )
                fig.update_traces(marker=dict(size=12))
                fig.update_layout(height=250)
                figs.append(fig)

            # Property value summary table
            latest_vals = {}
            for _, r in df_props.iterrows():
                prop = r.get("property", "")
                latest_vals[prop] = r.get("current")
            summary_rows = [{"Property": k, "Value": v} for k, v in sorted(latest_vals.items())]
            if summary_rows:
                df_summary = pd.DataFrame(summary_rows)
                fig = go.Figure(go.Table(
                    header=dict(values=["Property", "Value"]),
                    cells=dict(values=[df_summary["Property"], df_summary["Value"]]),
                ))
                fig.update_layout(title="VHAL Property Summary (Latest Values)", height=600)
                figs.append(fig)

    # Power state
    df_power = dfs.get("Power_Boot", pd.DataFrame())
    if not df_power.empty:
        power_info = "<h4>Power Events</h4><ul>"
        for _, row in df_power.iterrows():
            dt = row.get("datetime", "")
            reason = row.get("bootupReason", "unknown")
            power_info += f"<li>{dt}: Boot — {reason}</li>"
        power_info += "</ul>"
        return power_info + _figs_to_html(figs)

    # Drive state
    df_speed_change = dfs.get("Vehicle_SpeedChanged", pd.DataFrame())
    df_parking = dfs.get("Vehicle_ParkingBrakeChanged", pd.DataFrame())

    if not df_speed_change.empty and "current.speedKmh" in df_speed_change.columns:
        fig = px.line(
            df_speed_change,
            x="datetime",
            y="current.speedKmh",
            title="Speed (from DriveStateCollector)",
            markers=True,
        )
        fig.update_layout(height=300)
        figs.append(fig)

    if not figs:
        return "<p>No vehicle data available.</p>"
    return _figs_to_html(figs)


def build_storage_battery(dfs: dict) -> str:
    """Section 8: Storage & Battery."""
    figs = []

    # Battery
    df_bat = dfs.get("Battery_Level", pd.DataFrame())
    if not df_bat.empty:
        bat_expanded = expand_samples(df_bat)
        if "level" in bat_expanded.columns and "datetime" in bat_expanded.columns:
            fig = px.line(
                bat_expanded,
                x="datetime",
                y="level",
                title="Battery Level Over Time (%)",
                markers=True,
            )
            fig.update_layout(height=350)
            figs.append(fig)

        if "temperatureTenthsC" in bat_expanded.columns:
            bat_expanded["tempC"] = bat_expanded["temperatureTenthsC"] / 10
            fig = px.line(
                bat_expanded,
                x="datetime",
                y="tempC",
                title="Battery Temperature (°C)",
                markers=True,
            )
            fig.update_layout(height=300)
            figs.append(fig)

    # Storage
    df_storage = dfs.get("Storage_Usage", pd.DataFrame())
    if not df_storage.empty and "usagePercent" in df_storage.columns:
        if len(df_storage) > 1:
            fig = px.line(
                df_storage,
                x="datetime",
                y="usagePercent",
                title="Storage Usage (%)",
                markers=True,
            )
        else:
            fig = px.bar(
                df_storage,
                x=["Used", "Available"],
                y=[df_storage.iloc[0].get("usedBytes", 0) / 1e9,
                   df_storage.iloc[0].get("availableBytes", 0) / 1e9],
                title=f"Storage ({df_storage.iloc[0].get('usagePercent', 0):.1f}% used)",
                labels={"x": "", "y": "GB"},
            )
        fig.update_layout(height=300)
        figs.append(fig)

    if not figs:
        return "<p>No storage/battery data available.</p>"
    return _figs_to_html(figs)


def build_media(dfs: dict) -> str:
    """Section 9: Media playback."""
    figs = []

    df_playback = dfs.get("Media_PlaybackChanged", pd.DataFrame())
    df_track = dfs.get("Media_TrackChanged", pd.DataFrame())

    if not df_playback.empty:
        # Playback state timeline
        state_map = {0: "NONE", 1: "STOPPED", 2: "PAUSED", 3: "PLAYING",
                     6: "BUFFERING", 7: "ERROR"}
        if "current.stateLabel" in df_playback.columns:
            df_playback["state"] = df_playback["current.stateLabel"]
        elif "current.state" in df_playback.columns:
            df_playback["state"] = df_playback["current.state"].map(state_map).fillna("UNKNOWN")

        if "current.package" in df_playback.columns:
            df_playback["source"] = df_playback["current.package"].apply(
                lambda x: x.split(".")[-1] if isinstance(x, str) else "unknown"
            )
            # Source distribution
            src_counts = df_playback["source"].value_counts().reset_index()
            src_counts.columns = ["Source", "Events"]
            fig = px.pie(
                src_counts,
                values="Events",
                names="Source",
                title="Media Source Distribution",
            )
            fig.update_layout(height=350)
            figs.append(fig)

        if "state" in df_playback.columns:
            fig = px.scatter(
                df_playback,
                x="datetime",
                y="state",
                color="source" if "source" in df_playback.columns else None,
                title="Media Playback State Over Time",
                size_max=12,
            )
            fig.update_traces(marker=dict(size=10))
            fig.update_layout(height=300)
            figs.append(fig)

    if not df_track.empty:
        # Track change frequency
        df_track["time_bin"] = df_track["datetime"].dt.floor("1min")
        track_rate = df_track.groupby("time_bin").size().reset_index(name="skips")
        if len(track_rate) > 1:
            fig = px.bar(
                track_rate,
                x="time_bin",
                y="skips",
                title="Track Changes Per Minute",
            )
            fig.update_layout(height=300)
            figs.append(fig)

    if not figs:
        return "<p>No media data available.</p>"
    return _figs_to_html(figs)


def build_correlations(dfs: dict) -> str:
    """Section 10: Cross-collector correlations."""
    figs = []

    # Touch rate vs Frame drops
    touch_dfs = [dfs.get(a, pd.DataFrame()) for a in ("Touch_Down", "Touch_Up", "Touch_Swipe")]
    all_touch = pd.concat([d for d in touch_dfs if not d.empty], ignore_index=True)
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())

    if not all_touch.empty and not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            # Bin touches per 30s
            all_touch["time_bin"] = all_touch["datetime"].dt.floor("30s")
            touch_rate = all_touch.groupby("time_bin").size().reset_index(name="touches")

            fps_exp["time_bin"] = fps_exp["datetime"].dt.floor("30s")
            drops = fps_exp.groupby("time_bin")["dropped"].sum().reset_index()

            merged = pd.merge(touch_rate, drops, on="time_bin", how="outer").fillna(0)
            if len(merged) > 2:
                fig = make_subplots(specs=[[{"secondary_y": True}]])
                fig.add_trace(
                    go.Bar(x=merged["time_bin"], y=merged["touches"], name="Touches", opacity=0.6),
                    secondary_y=False,
                )
                fig.add_trace(
                    go.Scatter(x=merged["time_bin"], y=merged["dropped"], name="Dropped Frames",
                               mode="lines+markers", line=dict(color="red")),
                    secondary_y=True,
                )
                fig.update_layout(title="Touch Rate vs Dropped Frames", height=400)
                fig.update_yaxes(title_text="Touches", secondary_y=False)
                fig.update_yaxes(title_text="Dropped Frames", secondary_y=True)
                figs.append(fig)

    # App switch → Memory delta
    df_focus = dfs.get("App_FocusChanged", pd.DataFrame())
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_focus.empty and not df_mem.empty:
        mem_exp = expand_samples(df_mem)
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns and not df_focus.empty:
            fig = make_subplots(specs=[[{"secondary_y": True}]])
            fig.add_trace(
                go.Scatter(x=mem_exp["datetime"], y=mem_exp["availMb"],
                           name="Available Memory (MB)", mode="lines"),
                secondary_y=False,
            )
            # Add app switch markers
            for _, row in df_focus.iterrows():
                fig.add_vline(x=row["datetime"], line_dash="dot", line_color="rgba(255,0,0,0.3)")
            fig.update_layout(
                title="Memory vs App Switches (red lines = focus change)",
                height=400,
            )
            figs.append(fig)

    # Boot → First interaction latency
    df_power = dfs.get("Power_Boot", pd.DataFrame())
    if not df_power.empty and not all_touch.empty:
        boot_ts = df_power["timestamp"].min()
        first_touch_ts = all_touch["timestamp"].min()
        latency_s = (first_touch_ts - boot_ts) / 1000
        figs_html = f"<p><strong>Boot → First Touch Latency:</strong> {latency_s:.1f}s</p>"
        return figs_html + _figs_to_html(figs)

    if not figs:
        return "<p>Insufficient cross-collector data for correlations.</p>"
    return _figs_to_html(figs)


# ---------------------------------------------------------------------------
# HTML Template
# ---------------------------------------------------------------------------

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Telemetry Analysis Report</title>
<script src="https://cdn.plot.ly/plotly-2.32.0.min.js"></script>
<style>
* {{ box-sizing: border-box; margin: 0; padding: 0; }}
body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
       background: #1a1a2e; color: #e0e0e0; display: flex; }}
nav {{ position: fixed; top: 0; left: 0; width: 220px; height: 100vh;
       background: #16213e; padding: 20px 10px; overflow-y: auto; z-index: 100; }}
nav h2 {{ color: #0f9b8e; margin-bottom: 16px; font-size: 14px; text-transform: uppercase; }}
nav a {{ display: block; color: #a0a0c0; text-decoration: none; padding: 8px 12px;
         border-radius: 6px; margin-bottom: 4px; font-size: 13px; }}
nav a:hover {{ background: #1a1a3e; color: #0f9b8e; }}
main {{ margin-left: 220px; padding: 30px; width: calc(100% - 220px); }}
section {{ margin-bottom: 60px; }}
h2 {{ color: #0f9b8e; border-bottom: 1px solid #2a2a4e; padding-bottom: 10px; margin-bottom: 20px; }}
h3 {{ color: #7878a8; margin: 20px 0 10px; }}
.stats-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
               gap: 16px; margin-bottom: 30px; }}
.stat-card {{ background: #16213e; border-radius: 12px; padding: 20px; text-align: center; }}
.stat-card h3 {{ color: #0f9b8e; font-size: 28px; margin-bottom: 4px; }}
.stat-card p {{ color: #8888a8; font-size: 13px; }}
.js-plotly-plot {{ margin-bottom: 20px; }}
.generated {{ text-align: center; color: #555; font-size: 12px; margin-top: 40px; }}
</style>
</head>
<body>
<nav>
  <h2>Telemetry Report</h2>
  <a href="#overview">Overview</a>
  <a href="#app-usage">App Usage</a>
  <a href="#touch">Touch Interaction</a>
  <a href="#audio">Audio & Volume</a>
  <a href="#performance">Performance</a>
  <a href="#network">Network</a>
  <a href="#vehicle">Vehicle State</a>
  <a href="#storage-battery">Storage & Battery</a>
  <a href="#media">Media</a>
  <a href="#correlations">Correlations</a>
</nav>
<main>
  <section id="overview">
    <h2>1. Session Overview</h2>
    {overview}
  </section>
  <section id="app-usage">
    <h2>2. App Usage</h2>
    {app_usage}
  </section>
  <section id="touch">
    <h2>3. Touch Interaction</h2>
    {touch}
  </section>
  <section id="audio">
    <h2>4. Audio & Volume</h2>
    {audio}
  </section>
  <section id="performance">
    <h2>5. Memory & Performance</h2>
    {performance}
  </section>
  <section id="network">
    <h2>6. Connectivity & Network</h2>
    {network}
  </section>
  <section id="vehicle">
    <h2>7. Vehicle State</h2>
    {vehicle}
  </section>
  <section id="storage-battery">
    <h2>8. Storage & Battery</h2>
    {storage_battery}
  </section>
  <section id="media">
    <h2>9. Media</h2>
    {media}
  </section>
  <section id="correlations">
    <h2>10. Cross-Collector Correlations</h2>
    {correlations}
  </section>
  <p class="generated">Generated {generated_at} — AAOS DataCollector Telemetry Analysis</p>
</main>
</body>
</html>"""


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 generate_report.py <path_to_jsonl_dir_or_files...>")
        print("Example: python3 generate_report.py telemetry-logs/")
        sys.exit(1)

    paths = sys.argv[1:]
    print(f"[1/5] Ingesting from: {paths}")
    events = ingest(paths)
    print(f"       → {len(events)} events loaded")

    if not events:
        print("ERROR: No events found. Check the input path.")
        sys.exit(1)

    print("[2/5] Normalizing data...")
    dfs = normalize(events)
    print(f"       → {len(dfs)} action types")

    print("[3/5] Building report sections...")
    sections = {
        "overview": build_overview(events, dfs),
        "app_usage": build_app_usage(dfs),
        "touch": build_touch(dfs),
        "audio": build_audio(dfs),
        "performance": build_performance(dfs),
        "network": build_network(dfs),
        "vehicle": build_vehicle(dfs),
        "storage_battery": build_storage_battery(dfs),
        "media": build_media(dfs),
        "correlations": build_correlations(dfs),
    }

    print("[4/5] Assembling HTML...")
    html = HTML_TEMPLATE.format(
        generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        **sections,
    )

    print("[5/5] Writing output...")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    output_path = OUTPUT_DIR / f"report_{timestamp}.html"
    with open(output_path, "w") as f:
        f.write(html)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"\n✓ Report generated: {output_path} ({size_mb:.1f} MB)")
    print(f"  Open in browser: file://{output_path.resolve()}")


if __name__ == "__main__":
    main()
