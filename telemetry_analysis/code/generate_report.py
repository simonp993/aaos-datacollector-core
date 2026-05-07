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

# Consistent colors per display across ALL plots
DISPLAY_COLORS = {
    0: "#0f9b8e",  # Center Screen — teal
    1: "#f39c12",  # Instrument Cluster — orange
    2: "#5b8def",  # Passenger Screen — blue
    3: "#9b59b6",  # Rear Passenger — purple
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


def _short_name(pkg: str) -> str:
    """Get a readable short name from a package string."""
    if not pkg or pkg == "nan" or pkg == "None":
        return "unknown"
    parts = pkg.split(".")
    if len(parts) >= 3:
        last = parts[-1]
        if last in ("porsche", "mib4"):
            return parts[-2] if len(parts) > 2 else last
        return last
    return parts[-1] if parts else pkg


# ---------------------------------------------------------------------------
# Section Builders
# ---------------------------------------------------------------------------


def build_overview(events: list[dict], dfs: dict) -> str:
    """Section 1: Session overview."""
    figs = []

    # Event count by collector — no color coding, larger height for labels
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
        text="Count",
    )
    fig.update_traces(marker_color="#0f9b8e")
    fig.update_layout(height=max(400, len(df_counts) * 28), showlegend=False,
                      yaxis=dict(tickfont=dict(size=11)), margin=dict(l=180))
    figs.append(fig)

    # Event density — line graph per collector grouped by 1 minute
    all_rows = []
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        ts = e.get("timestamp")
        if ts:
            all_rows.append({"collector": sig, "datetime": pd.to_datetime(ts, unit="ms")})
    if all_rows:
        df_all = pd.DataFrame(all_rows)
        df_all["time_bin"] = df_all["datetime"].dt.floor("1min")
        density = df_all.groupby(["time_bin", "collector"]).size().reset_index(name="count")
        fig = px.line(
            density,
            x="time_bin",
            y="count",
            color="collector",
            title="Event Rate Over Time (per minute)",
            markers=True,
        )
        fig.update_layout(height=450, xaxis_title="Time", yaxis_title="Events / min")
        figs.append(fig)

    # Data volume per collector per minute (KB/min)
    size_rows = []
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        ts = e.get("timestamp")
        if ts:
            event_size = len(json.dumps(e, default=str))
            size_rows.append({
                "collector": sig,
                "datetime": pd.to_datetime(ts, unit="ms"),
                "bytes": event_size,
            })
    if size_rows:
        df_size = pd.DataFrame(size_rows)
        df_size["time_bin"] = df_size["datetime"].dt.floor("1min")
        kb_per_min = df_size.groupby(["time_bin", "collector"])["bytes"].sum().reset_index()
        kb_per_min["KB"] = kb_per_min["bytes"] / 1024
        fig = px.line(
            kb_per_min,
            x="time_bin",
            y="KB",
            color="collector",
            title="Data Volume per Collector (KB / minute)",
            markers=True,
        )
        fig.update_layout(height=450, xaxis_title="Time", yaxis_title="KB / min")
        figs.append(fig)

    # Session timeline — single row, no color
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
        session_summary["duration"] = (
            (session_summary["end"] - session_summary["start"]) / 60000
        ).apply(lambda x: f"{x:.1f} min")
        # Single row — they can't overlap
        fig = px.timeline(
            session_summary,
            x_start="start_dt",
            x_end="end_dt",
            y=["Sessions"] * len(session_summary),
            title="Session Timelines",
            text="duration",
        )
        fig.update_traces(marker_color="#0f9b8e")
        fig.update_layout(height=200, showlegend=False)
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
    """Section 2: App usage — per display, ordered by frequency."""
    df = dfs.get("App_FocusChanged")
    if df is None or df.empty:
        return "<p>No App_FocusChanged data available.</p>"

    figs = []

    # Extract package and class names
    df["current_pkg"] = df.apply(
        lambda r: str(r.get("current.package", "") or ""), axis=1
    )
    df["previous_pkg"] = df.apply(
        lambda r: str(r.get("previous.package", "") or ""), axis=1
    )
    df["current_class"] = df.apply(
        lambda r: str(r.get("current.class", "") or ""), axis=1
    )
    df["display_id"] = df.apply(
        lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0, axis=1
    )

    # Filter to physical displays (0-3)
    df_physical = df[df["display_id"] < 10].copy()

    # --- App Focus Frequency: ONE grouped bar chart, bars colored per screen ---
    display_ids = sorted(df_physical["display_id"].unique())
    all_focus_data = []
    for did in display_ids:
        df_disp = df_physical[df_physical["display_id"] == did]
        if df_disp.empty:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        pkg_counts = df_disp["current_pkg"].value_counts().reset_index()
        pkg_counts.columns = ["Package", "Focus Count"]
        pkg_counts["Short"] = pkg_counts["Package"].apply(_short_name)
        pkg_counts["Display"] = name
        pkg_counts["display_id"] = did
        # Filter out unknowns (empty package)
        pkg_counts = pkg_counts[pkg_counts["Short"] != "unknown"]
        all_focus_data.append(pkg_counts)

    if all_focus_data:
        df_focus_all = pd.concat(all_focus_data, ignore_index=True)
        fig = go.Figure()
        for did in display_ids:
            name = DISPLAY_NAMES.get(did, f"Display {did}")
            color = DISPLAY_COLORS.get(did, "#888888")
            df_d = df_focus_all[df_focus_all["display_id"] == did]
            if df_d.empty:
                continue
            fig.add_trace(go.Bar(
                x=df_d["Short"], y=df_d["Focus Count"],
                name=name, marker_color=color,
                hovertext=df_d["Package"],
            ))
        fig.update_layout(
            title="App Focus Frequency (all displays)",
            barmode="group", height=400,
            xaxis={"categoryorder": "total descending"},
            legend_title="Display",
        )
        figs.append(fig)

    # --- Time in Package: ONE grouped bar chart, bars colored per screen ---
    df_sorted = df_physical.sort_values("datetime").reset_index(drop=True)
    all_time_data = []
    for did in display_ids:
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        durations = []
        for i in range(len(df_disp) - 1):
            pkg = str(df_disp.iloc[i]["current_pkg"])
            if not pkg or pkg == "nan":
                continue
            dur = (df_disp.iloc[i + 1]["timestamp"] - df_disp.iloc[i]["timestamp"]) / 1000
            if 0 < dur < 3600:
                durations.append({"package": pkg, "duration_s": dur})
        if durations:
            df_dur = pd.DataFrame(durations)
            app_time = df_dur.groupby("package")["duration_s"].sum().reset_index()
            app_time["Short"] = app_time["package"].apply(_short_name)
            app_time["Display"] = name
            app_time["display_id"] = did
            # Filter out unknowns
            app_time = app_time[app_time["Short"] != "unknown"]
            all_time_data.append(app_time)

    if all_time_data:
        df_time_all = pd.concat(all_time_data, ignore_index=True)
        fig = go.Figure()
        for did in display_ids:
            name = DISPLAY_NAMES.get(did, f"Display {did}")
            color = DISPLAY_COLORS.get(did, "#888888")
            df_d = df_time_all[df_time_all["display_id"] == did]
            if df_d.empty:
                continue
            fig.add_trace(go.Bar(
                x=df_d["Short"], y=df_d["duration_s"],
                name=name, marker_color=color,
                hovertext=df_d["package"],
                text=df_d["duration_s"].apply(lambda x: f"{x:.0f}s"),
            ))
        fig.update_layout(
            title="Time in Package (all displays)",
            barmode="group", height=400,
            xaxis={"categoryorder": "total descending"},
            yaxis_title="Duration (s)",
            legend_title="Display",
        )
        figs.append(fig)

    # --- Time in Class (more granular) ---
    for did in display_ids:
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        durations = []
        for i in range(len(df_disp) - 1):
            cls = str(df_disp.iloc[i]["current_class"])
            pkg = str(df_disp.iloc[i]["current_pkg"])
            dur = (df_disp.iloc[i + 1]["timestamp"] - df_disp.iloc[i]["timestamp"]) / 1000
            if 0 < dur < 3600:
                cls_short = cls.split(".")[-1] if cls else "unknown"
                durations.append({"class": cls_short, "package": _short_name(pkg), "duration_s": dur})
        if durations:
            df_dur = pd.DataFrame(durations)
            class_time = df_dur.groupby(["class", "package"])["duration_s"].sum().reset_index()
            class_time = class_time.sort_values("duration_s", ascending=False).head(15)
            fig = px.bar(
                class_time,
                x="class",
                y="duration_s",
                color="package",
                title=f"Time in Class — {name} (Top 15)",
                text=class_time["duration_s"].apply(lambda x: f"{x:.0f}s"),
            )
            fig.update_layout(height=400, xaxis={"categoryorder": "total descending"})
            figs.append(fig)

    # --- App Package Transition Flow (Sankey) — one per display ---
    for did in display_ids:
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        transitions = pd.DataFrame({
            "previous_pkg": df_disp["current_pkg"].shift(1),
            "current_pkg": df_disp["current_pkg"],
        }).dropna()
        transitions = transitions[transitions["previous_pkg"].str.len() > 0]
        transitions = transitions[transitions["previous_pkg"] != transitions["current_pkg"]]
        if len(transitions) == 0:
            continue
        trans_counts = transitions.groupby(["previous_pkg", "current_pkg"]).size().reset_index(name="count")
        trans_counts = trans_counts.sort_values("count", ascending=False).head(20)
        all_pkgs = list(set(trans_counts["previous_pkg"].tolist() + trans_counts["current_pkg"].tolist()))
        node_labels = [_short_name(p) for p in all_pkgs]
        node_map = {p: i for i, p in enumerate(all_pkgs)}
        fig = go.Figure(go.Sankey(
            node=dict(label=node_labels, pad=15, thickness=20),
            link=dict(
                source=[node_map[r["previous_pkg"]] for _, r in trans_counts.iterrows()],
                target=[node_map[r["current_pkg"]] for _, r in trans_counts.iterrows()],
                value=trans_counts["count"].tolist(),
            ),
        ))
        fig.update_layout(title=f"Package Transition Flow — {name}", height=450)
        figs.append(fig)

    # --- Class Transition Flow (Sankey) — one per display ---
    for did in display_ids:
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        cls_data = pd.DataFrame({
            "previous_class": df_disp["current_class"].shift(1),
            "current_class": df_disp["current_class"],
        }).dropna()
        cls_data = cls_data[cls_data["previous_class"].str.len() > 0]
        cls_data = cls_data[cls_data["previous_class"] != cls_data["current_class"]]
        if len(cls_data) == 0:
            continue
        cls_data["prev_short"] = cls_data["previous_class"].apply(lambda x: x.split(".")[-1])
        cls_data["curr_short"] = cls_data["current_class"].apply(lambda x: x.split(".")[-1])
        cls_counts = cls_data.groupby(["prev_short", "curr_short"]).size().reset_index(name="count")
        cls_counts = cls_counts.sort_values("count", ascending=False).head(20)
        if len(cls_counts) == 0:
            continue
        all_cls = list(set(cls_counts["prev_short"].tolist() + cls_counts["curr_short"].tolist()))
        cls_map = {c: i for i, c in enumerate(all_cls)}
        fig = go.Figure(go.Sankey(
            node=dict(label=all_cls, pad=15, thickness=20),
            link=dict(
                source=[cls_map[r["prev_short"]] for _, r in cls_counts.iterrows()],
                target=[cls_map[r["curr_short"]] for _, r in cls_counts.iterrows()],
                value=cls_counts["count"].tolist(),
            ),
        ))
        fig.update_layout(title=f"Class Transition Flow — {name}", height=450)
        figs.append(fig)

    # --- App Usage Timeline (Gantt) ---
    for did in display_ids[:2]:
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        timeline_data = []
        for i in range(len(df_disp) - 1):
            pkg = str(df_disp.iloc[i]["current_pkg"]) if pd.notna(df_disp.iloc[i]["current_pkg"]) else ""
            start = df_disp.iloc[i]["datetime"]
            end = df_disp.iloc[i + 1]["datetime"]
            short = _short_name(pkg)
            timeline_data.append({"App": short, "Start": start, "End": end})
        if timeline_data:
            df_timeline = pd.DataFrame(timeline_data)
            df_timeline["dur_s"] = (df_timeline["End"] - df_timeline["Start"]).dt.total_seconds()
            df_timeline = df_timeline[df_timeline["dur_s"] < 600]
            if not df_timeline.empty:
                fig = px.timeline(
                    df_timeline,
                    x_start="Start",
                    x_end="End",
                    y="App",
                    title=f"App Usage Timeline — {name}",
                    color="App",
                )
                fig.update_layout(height=350, showlegend=False)
                figs.append(fig)

    return _figs_to_html(figs)


def build_touch(dfs: dict) -> str:
    """Section 3: Touch interaction — finer heatmaps, include swipes."""
    figs = []
    touch_actions = ["Touch_Down", "Touch_Up", "Touch_Swipe"]
    touch_dfs = [dfs[a] for a in touch_actions if a in dfs and not dfs[a].empty]

    if not touch_dfs:
        return "<p>No touch data available.</p>"

    # Instrument cluster (display 1) is not a touchscreen — exclude it
    TOUCH_EXCLUDED_DISPLAYS = {1}

    # Combine Down + Swipe for heatmap
    heatmap_actions = ["Touch_Down", "Touch_Swipe"]
    heatmap_dfs = [dfs[a] for a in heatmap_actions if a in dfs and not dfs[a].empty]
    df_heatmap = pd.concat(heatmap_dfs, ignore_index=True) if heatmap_dfs else pd.DataFrame()

    note_html = ""
    # Per-display heatmaps with fine resolution
    if not df_heatmap.empty and "displayId" in df_heatmap.columns:
        display_ids = sorted(d for d in df_heatmap["displayId"].dropna().unique() if int(d) not in TOUCH_EXCLUDED_DISPLAYS)
        for did in display_ids:
            did_int = int(did)
            df_disp = df_heatmap[df_heatmap["displayId"] == did_int]
            if df_disp.empty:
                continue
            name = DISPLAY_NAMES.get(did_int, f"Display {did_int}")
            res = DISPLAY_RESOLUTIONS.get(did_int, (1920, 720))
            n_points = len(df_disp)

            # Adaptive bin size: fewer points = larger bins for smoother appearance
            if n_points < 50:
                bin_size = 80  # large bins for sparse data
            elif n_points < 200:
                bin_size = 40
            else:
                bin_size = 20  # fine bins for dense data

            nbinsx = max(10, res[0] // bin_size)
            nbinsy = max(8, res[1] // bin_size)

            # Scale figure to match pixel aspect ratio
            fig_width = 700
            fig_height = int(fig_width * res[1] / res[0]) + 80  # +80 for title/margins

            fig = go.Figure()
            fig.add_trace(go.Histogram2d(
                x=df_disp["x"].tolist(),
                y=df_disp["y"].tolist(),
                nbinsx=nbinsx,
                nbinsy=nbinsy,
                colorscale=[[0, "rgba(30,30,50,0)"], [0.15, "#4575b4"],
                            [0.35, "#91bfdb"], [0.5, "#fee090"],
                            [0.7, "#fc8d59"], [1.0, "#d73027"]],
                showscale=True,
                colorbar=dict(title="Touches"),
                zsmooth="best",
            ))
            # Draw display boundary rectangle
            fig.add_shape(
                type="rect", x0=0, y0=0, x1=res[0], y1=res[1],
                line=dict(color="#555555", width=2),
            )
            fig.update_layout(
                title=f"Touch Heatmap — {name} (n={n_points}, taps + swipes)",
                xaxis=dict(range=[0, res[0]], title="X", showgrid=False, constrain="domain"),
                yaxis=dict(range=[res[1], 0], title="Y", showgrid=False, scaleanchor="x", constrain="domain"),
                height=fig_height,
                width=fig_width,
                plot_bgcolor="#1e1e32",
            )
            figs.append(fig)

        # Note about data density
        total_touches = len(df_heatmap)
        note_html = (
            f"<p><em>Note: {total_touches} touch events in this recording. "
            "Smooth heatmaps (like eye-tracking studies) require 1000+ data points. "
            "The current data shows individual touch clusters rather than smooth gradients. "
            "Longer recording sessions with more interaction will produce smoother maps.</em></p>"
        )

    # Touch rate over time
    all_touch = pd.concat(touch_dfs, ignore_index=True) if touch_dfs else pd.DataFrame()
    if not all_touch.empty and "datetime" in all_touch.columns:
        all_touch["time_bin"] = all_touch["datetime"].dt.floor("30s")
        rate = all_touch.groupby("time_bin").size().reset_index(name="touches")
        rate["touches_per_min"] = rate["touches"] * 2
        fig = px.line(
            rate,
            x="time_bin",
            y="touches_per_min",
            title="Touch Rate Over Time (per minute, all types incl. swipes)",
            markers=True,
        )
        fig.update_layout(height=300)
        figs.append(fig)

    # Touch duration distribution
    df_down = dfs.get("Touch_Down", pd.DataFrame())
    df_up = dfs.get("Touch_Up", pd.DataFrame())
    if not df_down.empty and not df_up.empty:
        downs = df_down["timestamp"].sort_values().values
        ups = df_up["timestamp"].sort_values().values
        durations = []
        up_idx = 0
        for d in downs:
            while up_idx < len(ups) and ups[up_idx] < d:
                up_idx += 1
            if up_idx < len(ups):
                dur = ups[up_idx] - d
                if 0 < dur < 5000:
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

    # Touches per App — one grouped bar chart, bars colored per display
    df_focus = dfs.get("App_FocusChanged", pd.DataFrame())
    if not all_touch.empty and not df_focus.empty and "displayId" in all_touch.columns:
        df_focus_sorted = df_focus.sort_values("timestamp").reset_index(drop=True)
        focus_pkg = df_focus_sorted.apply(
            lambda r: str(r.get("current.package", "") or ""), axis=1
        )
        focus_display = df_focus_sorted.apply(
            lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0, axis=1
        )
        focus_ts = df_focus_sorted["timestamp"].values

        touch_display_ids = sorted(d for d in all_touch["displayId"].dropna().unique() if int(d) not in TOUCH_EXCLUDED_DISPLAYS)
        fig_touches_app = go.Figure()
        has_data = False
        for did in touch_display_ids:
            did_int = int(did)
            name = DISPLAY_NAMES.get(did_int, f"Display {did_int}")
            color = DISPLAY_COLORS.get(did_int, "#888888")

            mask = focus_display == did_int
            disp_focus_ts = focus_ts[mask]
            disp_focus_pkg = focus_pkg[mask].values

            if len(disp_focus_ts) == 0:
                continue

            df_touch_disp = all_touch[all_touch["displayId"] == did_int]
            if df_touch_disp.empty:
                continue

            touch_ts_arr = df_touch_disp["timestamp"].values
            app_for_touch = []
            for t_ts in touch_ts_arr:
                idx = disp_focus_ts.searchsorted(t_ts, side="right") - 1
                if idx >= 0:
                    app_for_touch.append(_short_name(disp_focus_pkg[idx]))
                else:
                    app_for_touch.append("unknown")

            touch_app_counts = pd.Series(app_for_touch).value_counts().reset_index()
            touch_app_counts.columns = ["App", "Touches"]
            touch_app_counts = touch_app_counts[touch_app_counts["App"] != "unknown"]
            if not touch_app_counts.empty:
                fig_touches_app.add_trace(go.Bar(
                    x=touch_app_counts["App"], y=touch_app_counts["Touches"],
                    name=name, marker_color=color,
                    text=touch_app_counts["Touches"],
                ))
                has_data = True

        if has_data:
            fig_touches_app.update_layout(
                title="Touches per App (all displays)",
                barmode="group", height=400,
                xaxis={"categoryorder": "total descending"},
                legend_title="Display",
            )
            figs.append(fig_touches_app)

    return note_html + _figs_to_html(figs)


def build_audio(dfs: dict) -> str:
    """Section 4: Audio & Volume."""
    figs = []

    df_snap = dfs.get("Audio_Snapshot", pd.DataFrame())
    df_change = dfs.get("Audio_StateChanged", pd.DataFrame())

    if not df_snap.empty:
        vol_rows = []
        for _, row in df_snap.iterrows():
            dt = row.get("datetime")
            for col in row.index:
                if "carVolumeGroups" in col and isinstance(row[col], str) and "/" in row[col]:
                    group_name = col.replace("carVolumeGroups.", "")
                    val = row[col]
                    num, den = val.split("/")
                    try:
                        vol_rows.append({
                            "datetime": dt,
                            "group": group_name,
                            "level": int(num),
                            "max": int(den),
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

    if not df_change.empty:
        change_rows = []
        for _, row in df_change.iterrows():
            dt = row.get("datetime")
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

    # Memory — show total and available
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    mem_expanded = pd.DataFrame()
    if not df_mem.empty:
        mem_expanded = expand_samples(df_mem)
        if "availMb" in mem_expanded.columns and "datetime" in mem_expanded.columns:
            total_mb = None
            if "totalMem" in df_mem.columns:
                total_vals = df_mem["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_mb = int(total_vals.iloc[0]) / (1024 * 1024)

            fig = go.Figure()
            fig.add_trace(go.Scatter(
                x=mem_expanded["datetime"],
                y=mem_expanded["availMb"],
                mode="lines+markers",
                name="Available (MB)",
                line=dict(color="#0f9b8e"),
            ))
            if total_mb:
                fig.add_hline(y=total_mb, line_dash="dash", line_color="gray",
                              annotation_text=f"Total: {total_mb:.0f} MB")
            fig.update_layout(title="Memory Over Time", height=350, yaxis_title="MB")
            figs.append(fig)

    # Frame Rate
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    fps_expanded = pd.DataFrame()
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

        # Dropped frames — line chart
        if "dropped" in fps_expanded.columns:
            fig = px.line(
                fps_expanded,
                x="datetime",
                y="dropped",
                title="Dropped Frames Over Time",
                markers=True,
            )
            fig.update_traces(line=dict(color="red"))
            fig.update_layout(height=300)
            figs.append(fig)

    if not figs:
        return "<p>No performance data available.</p>"
    return _figs_to_html(figs)


def build_network(dfs: dict) -> str:
    """Section 6: Connectivity & Network — all transports, all apps."""
    figs = []

    # Signal strength
    df_signal = dfs.get("Connectivity_SignalStrength", pd.DataFrame())
    if not df_signal.empty:
        sig_expanded = expand_samples(df_signal)
        if "signalStrengthDbm" in sig_expanded.columns and "datetime" in sig_expanded.columns:
            transport = "WiFi"
            if "transport" in df_signal.columns:
                transports = df_signal["transport"].dropna().unique()
                transport = ", ".join(str(t) for t in transports)
            fig = px.line(
                sig_expanded,
                x="datetime",
                y="signalStrengthDbm",
                title=f"Signal Strength ({transport}) (dBm)",
                markers=True,
            )
            fig.update_layout(height=350)
            figs.append(fig)

            # Signal Quality (%) + Bandwidth (Mbps)
            has_bw = ("maxDownstreamBandwidthKbps" in sig_expanded.columns
                      and "maxUpstreamBandwidthKbps" in sig_expanded.columns)
            # Compute quality: WiFi → 2*(dBm+100) clamped [0,100]
            sig_expanded["quality_pct"] = (2 * (sig_expanded["signalStrengthDbm"] + 100)).clip(0, 100)

            fig_q = make_subplots(specs=[[{"secondary_y": True}]])
            if has_bw:
                sig_expanded["downstream_mbps"] = sig_expanded["maxDownstreamBandwidthKbps"] / 1000
                sig_expanded["upstream_mbps"] = sig_expanded["maxUpstreamBandwidthKbps"] / 1000
                fig_q.add_trace(
                    go.Scatter(
                        x=sig_expanded["datetime"], y=sig_expanded["downstream_mbps"],
                        mode="lines", name="Max Download (Mbps)",
                        line=dict(color="#5b8def", width=1.5),
                    ),
                    secondary_y=True,
                )
                fig_q.add_trace(
                    go.Scatter(
                        x=sig_expanded["datetime"], y=sig_expanded["upstream_mbps"],
                        mode="lines", name="Max Upload (Mbps)",
                        line=dict(color="#f39c12", width=1.5),
                    ),
                    secondary_y=True,
                )
            # Quality line added last so it renders on top
            fig_q.add_trace(
                go.Scatter(
                    x=sig_expanded["datetime"], y=sig_expanded["quality_pct"],
                    mode="lines+markers", name="Signal Quality (%)",
                    line=dict(color="#2ecc71", width=3, dash="dash"),
                    marker=dict(size=4),
                ),
                secondary_y=False,
            )
            fig_q.update_layout(
                title=f"Signal Quality & Bandwidth ({transport})",
                height=350,
            )
            fig_q.update_yaxes(title_text="Quality (%)", range=[0, 105], secondary_y=False)
            fig_q.update_yaxes(title_text="Mbps", secondary_y=True)
            figs.append(fig_q)

    # Transport note
    df_avail = dfs.get("Connectivity_Available", pd.DataFrame())
    if not df_avail.empty and "transport" in df_avail.columns:
        transports = df_avail["transport"].unique()
        note = f"<p><em>Active transports: {', '.join(str(t) for t in transports)}. "
        note += "Mobile/Bluetooth/Ethernet would appear here if active on the device.</em></p>"
    else:
        note = "<p><em>Only WiFi data on emulator. Real device shows mobile, BT, ethernet too.</em></p>"

    # Total traffic
    df_wifi = dfs.get("Network_WifiTotal", pd.DataFrame())
    df_mobile = dfs.get("Network_MobileTotal", pd.DataFrame())
    traffic_traces = []
    if not df_wifi.empty and "datetime" in df_wifi.columns:
        traffic_traces.append(("WiFi RX", df_wifi, "rxBytes", "#0f9b8e"))
        traffic_traces.append(("WiFi TX", df_wifi, "txBytes", "#5b8def"))
    if not df_mobile.empty and "datetime" in df_mobile.columns:
        traffic_traces.append(("Mobile RX", df_mobile, "rxBytes", "#e74c3c"))
        traffic_traces.append(("Mobile TX", df_mobile, "txBytes", "#f39c12"))

    if traffic_traces:
        fig = go.Figure()
        for tname, df_t, col, color in traffic_traces:
            if col in df_t.columns:
                fig.add_trace(go.Scatter(
                    x=df_t["datetime"],
                    y=df_t[col] / (1024 * 1024),
                    mode="lines+markers",
                    name=tname,
                    line=dict(color=color),
                ))
        fig.update_layout(title="Network Traffic Over Time", height=350, yaxis_title="MB")
        figs.append(fig)

    # Per-app traffic — ALL apps
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    if not df_perapp.empty and "apps" in df_perapp.columns:
        app_totals = defaultdict(lambda: {"rx": 0, "tx": 0, "packages": []})
        for _, row in df_perapp.iterrows():
            apps_data = row.get("apps", [])
            if not apps_data:
                continue
            for app in apps_data:
                if not isinstance(app, dict):
                    continue
                pkgs = app.get("packages", [])
                uid = app.get("uid", 0)
                label = pkgs[0] if pkgs else f"uid:{uid}"
                rx = app.get("rxBytes", 0)
                tx = app.get("txBytes", 0)
                app_totals[label]["rx"] = max(app_totals[label]["rx"], rx)
                app_totals[label]["tx"] = max(app_totals[label]["tx"], tx)
                app_totals[label]["packages"] = pkgs

        if app_totals:
            app_traffic = []
            for label, data in app_totals.items():
                if label == "root":
                    display_name = "root (kernel/netd)"
                elif "location.fused" in label:
                    display_name = "system (fused=location+car+settings)"
                else:
                    display_name = _short_name(label)
                rx_mb = data["rx"] / (1024 * 1024)
                tx_mb = data["tx"] / (1024 * 1024)
                if rx_mb + tx_mb > 0.001:
                    app_traffic.append({
                        "App": display_name,
                        "RX (MB)": rx_mb,
                        "TX (MB)": tx_mb,
                        "Total (MB)": rx_mb + tx_mb,
                        "Full": label,
                    })
            if app_traffic:
                df_traffic = pd.DataFrame(app_traffic).sort_values("Total (MB)", ascending=True)
                fig = go.Figure()
                fig.add_trace(go.Bar(
                    y=df_traffic["App"], x=df_traffic["RX (MB)"],
                    name="RX", orientation="h", marker_color="#0f9b8e",
                ))
                fig.add_trace(go.Bar(
                    y=df_traffic["App"], x=df_traffic["TX (MB)"],
                    name="TX", orientation="h", marker_color="#5b8def",
                ))
                fig.update_layout(
                    title=f"Per-App Network Traffic (All {len(df_traffic)} apps, MB)",
                    barmode="group",
                    height=max(300, len(df_traffic) * 50),
                    xaxis_title="MB",
                )
                figs.append(fig)

    if not figs:
        return note + "<p>No network data available.</p>"
    return note + _figs_to_html(figs)


def build_vehicle(dfs: dict) -> str:
    """Section 7: Vehicle state from VHAL."""
    figs = []

    df_vhal = dfs.get("VHAL_ValuesChanged", pd.DataFrame())
    if not df_vhal.empty and "changes" in df_vhal.columns:
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

            # Speed
            df_speed = df_props[df_props["property"] == "PERF_VEHICLE_SPEED"].copy()
            if not df_speed.empty:
                df_speed["current"] = pd.to_numeric(df_speed["current"], errors="coerce")
                fig = px.line(df_speed, x="datetime", y="current", title="Vehicle Speed", markers=True)
                fig.update_layout(height=300, yaxis_title="km/h")
                figs.append(fig)

            # Gear
            df_gear = df_props[df_props["property"] == "GEAR_SELECTION"].copy()
            if not df_gear.empty:
                gear_map = {1: "P", 2: "R", 4: "N", 8: "D"}
                df_gear["gear_label"] = df_gear["current"].apply(
                    lambda x: gear_map.get(x, str(x)) if x is not None else "?"
                )
                fig = px.scatter(df_gear, x="datetime", y="gear_label", title="Gear Selection", text="gear_label")
                fig.update_traces(marker=dict(size=12))
                fig.update_layout(height=200)
                figs.append(fig)

    # Power
    df_power = dfs.get("Power_Boot", pd.DataFrame())
    power_html = ""
    if not df_power.empty:
        power_html = "<h4>Power Events</h4><ul>"
        for _, row in df_power.iterrows():
            dt = row.get("datetime", "")
            reason = row.get("bootupReason", "unknown")
            power_html += f"<li>{dt}: Boot — {reason}</li>"
        power_html += "</ul>"

    if not figs:
        return power_html + "<p>No vehicle data available.</p>"
    return power_html + _figs_to_html(figs)


def build_storage_battery(dfs: dict) -> str:
    """Section 8: Storage & Battery."""
    figs = []

    df_bat = dfs.get("Battery_Level", pd.DataFrame())
    if not df_bat.empty:
        bat_expanded = expand_samples(df_bat)
        if "level" in bat_expanded.columns and "datetime" in bat_expanded.columns:
            fig = px.line(bat_expanded, x="datetime", y="level", title="Battery Level (%)", markers=True)
            fig.update_layout(height=300)
            figs.append(fig)
        if "temperatureTenthsC" in bat_expanded.columns:
            bat_expanded["tempC"] = bat_expanded["temperatureTenthsC"] / 10
            fig = px.line(bat_expanded, x="datetime", y="tempC", title="Battery Temperature (°C)", markers=True)
            fig.update_layout(height=300)
            figs.append(fig)

    df_storage = dfs.get("Storage_Usage", pd.DataFrame())
    if not df_storage.empty and "usagePercent" in df_storage.columns:
        if len(df_storage) > 1:
            fig = px.line(df_storage, x="datetime", y="usagePercent", title="Storage Usage (%)", markers=True)
        else:
            used_gb = df_storage.iloc[0].get("usedBytes", 0) / 1e9
            avail_gb = df_storage.iloc[0].get("availableBytes", 0) / 1e9
            pct = df_storage.iloc[0].get("usagePercent", 0)
            fig = go.Figure(go.Bar(
                x=["Used", "Available"], y=[used_gb, avail_gb],
                marker_color=["#e74c3c", "#0f9b8e"],
            ))
            fig.update_layout(title=f"Storage ({pct:.1f}% used)", height=300, yaxis_title="GB")
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
        if "current.stateLabel" in df_playback.columns:
            df_playback["state"] = df_playback["current.stateLabel"]
        elif "current.state" in df_playback.columns:
            state_map = {0: "NONE", 1: "STOPPED", 2: "PAUSED", 3: "PLAYING", 6: "BUFFERING", 7: "ERROR"}
            df_playback["state"] = df_playback["current.state"].map(state_map).fillna("UNKNOWN")

        if "current.package" in df_playback.columns:
            df_playback["source"] = df_playback["current.package"].apply(
                lambda x: _short_name(str(x)) if pd.notna(x) else "unknown"
            )
            src_counts = df_playback["source"].value_counts().reset_index()
            src_counts.columns = ["Source", "Events"]
            fig = px.pie(src_counts, values="Events", names="Source", title="Media Source Distribution")
            fig.update_layout(height=350)
            figs.append(fig)

        if "state" in df_playback.columns:
            fig = px.scatter(
                df_playback, x="datetime", y="state",
                color="source" if "source" in df_playback.columns else None,
                title="Media Playback State Over Time",
            )
            fig.update_traces(marker=dict(size=10))
            fig.update_layout(height=300)
            figs.append(fig)

    if not df_track.empty:
        df_track["time_bin"] = df_track["datetime"].dt.floor("1min")
        track_rate = df_track.groupby("time_bin").size().reset_index(name="skips")
        if len(track_rate) > 1:
            fig = px.bar(track_rate, x="time_bin", y="skips", title="Track Changes Per Minute")
            fig.update_layout(height=300)
            figs.append(fig)

    if not figs:
        return "<p>No media data available.</p>"
    return _figs_to_html(figs)


def build_correlations(dfs: dict) -> str:
    """Section 10: Cross-collector correlations."""
    figs = []

    # Touch rate per display vs Dropped Frames
    touch_actions = ["Touch_Down", "Touch_Up", "Touch_Swipe"]
    touch_dfs_list = [dfs.get(a, pd.DataFrame()) for a in touch_actions]
    all_touch = pd.concat([d for d in touch_dfs_list if not d.empty], ignore_index=True)
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())

    if not all_touch.empty and not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            min_time = min(all_touch["datetime"].min(), fps_exp["datetime"].min())
            max_time = max(all_touch["datetime"].max(), fps_exp["datetime"].max())
            time_bins = pd.date_range(min_time.floor("30s"), max_time.ceil("30s"), freq="30s")

            fig = make_subplots(specs=[[{"secondary_y": True}]])

            # Touch rate per display as lines
            if "displayId" in all_touch.columns:
                display_ids = sorted(all_touch["displayId"].dropna().unique())
            else:
                display_ids = [0]

            for idx, did in enumerate(display_ids):
                did_int = int(did)
                if "displayId" in all_touch.columns:
                    df_disp = all_touch[all_touch["displayId"] == did_int]
                else:
                    df_disp = all_touch
                name = DISPLAY_NAMES.get(did_int, f"Display {did_int}")
                color = DISPLAY_COLORS.get(did_int, "#888888")
                df_disp_binned = df_disp.copy()
                df_disp_binned["time_bin"] = df_disp_binned["datetime"].dt.floor("30s")
                rate = df_disp_binned.groupby("time_bin").size().reset_index(name="touches")
                rate = rate.set_index("time_bin").reindex(time_bins, fill_value=0).reset_index()
                rate.columns = ["time_bin", "touches"]
                rate["touches_per_min"] = rate["touches"] * 2
                fig.add_trace(
                    go.Scatter(
                        x=rate["time_bin"], y=rate["touches_per_min"],
                        mode="lines", name=f"Touches — {name}",
                        line=dict(color=color),
                    ),
                    secondary_y=False,
                )

            # Dropped frames as bars
            fps_exp_binned = fps_exp.copy()
            fps_exp_binned["time_bin"] = fps_exp_binned["datetime"].dt.floor("30s")
            drops = fps_exp_binned.groupby("time_bin")["dropped"].sum().reset_index()
            fig.add_trace(
                go.Bar(
                    x=drops["time_bin"], y=drops["dropped"],
                    name="Dropped Frames", opacity=0.4, marker_color="red",
                ),
                secondary_y=True,
            )

            # FPS average as line on secondary axis
            if "fps" in fps_exp.columns:
                fps_binned = fps_exp_binned.groupby("time_bin")["fps"].mean().reset_index()
                fig.add_trace(
                    go.Scatter(
                        x=fps_binned["time_bin"], y=fps_binned["fps"],
                        mode="lines+markers", name="FPS (avg)",
                        line=dict(color="#2ecc71", width=2, dash="dot"),
                    ),
                    secondary_y=True,
                )

            fig.update_layout(title="Touch Rate vs Dropped Frames & FPS", height=450)
            fig.update_yaxes(title_text="Touches/min", secondary_y=False)
            fig.update_yaxes(title_text="Dropped / FPS", secondary_y=True)
            figs.append(fig)

    # Memory vs App Switches (per display)
    df_focus = dfs.get("App_FocusChanged", pd.DataFrame())
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_focus.empty and not df_mem.empty:
        mem_exp = expand_samples(df_mem)
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            fig = go.Figure()
            fig.add_trace(go.Scatter(
                x=mem_exp["datetime"], y=mem_exp["availMb"],
                mode="lines", name="Available Memory (MB)",
                line=dict(color="#0f9b8e", width=2),
            ))

            df_focus_copy = df_focus.copy()
            df_focus_copy["display_id"] = df_focus_copy.apply(
                lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0, axis=1
            )
            display_ids = sorted(df_focus_copy["display_id"].unique())

            for did in display_ids:
                if did >= 10:
                    continue
                name = DISPLAY_NAMES.get(did, f"Display {did}")
                df_d = df_focus_copy[df_focus_copy["display_id"] == did]
                color = DISPLAY_COLORS.get(did, "#888888")
                for _, row in df_d.iterrows():
                    fig.add_vline(x=row["datetime"], line_dash="dot", line_color=color, opacity=0.5)
                # Legend entry
                fig.add_trace(go.Scatter(
                    x=[df_d.iloc[0]["datetime"]], y=[mem_exp["availMb"].mean()],
                    mode="markers", name=f"Switch — {name}",
                    marker=dict(color=color, size=8), showlegend=True,
                ))

            fig.update_layout(title="Memory vs App Switches (colored by display)", height=400, yaxis_title="MB")
            figs.append(fig)

    # Boot → First interaction
    df_power = dfs.get("Power_Boot", pd.DataFrame())
    latency_html = ""
    if not df_power.empty and not all_touch.empty:
        boot_ts = df_power["timestamp"].min()
        first_touch_ts = all_touch["timestamp"].min()
        latency_s = (first_touch_ts - boot_ts) / 1000
        latency_html = f"<p><strong>Boot → First Touch Latency:</strong> {latency_s:.1f}s</p>"

    # --- App Lifecycle vs Network Traffic ---
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    df_focus_lc = dfs.get("App_FocusChanged", pd.DataFrame())
    if not df_perapp.empty and "apps" in df_perapp.columns and not df_focus_lc.empty:
        # Build per-app traffic timeline: compute rate (delta bytes) between samples
        traffic_rows = []
        for _, row in df_perapp.sort_values("timestamp").iterrows():
            ts = row.get("timestamp")
            dt = row.get("datetime")
            apps_data = row.get("apps", [])
            if not apps_data or not isinstance(apps_data, list):
                continue
            for app in apps_data:
                if not isinstance(app, dict):
                    continue
                pkgs = app.get("packages", [])
                label = pkgs[0] if pkgs else f"uid:{app.get('uid', 0)}"
                rx = app.get("rxBytes", 0)
                tx = app.get("txBytes", 0)
                traffic_rows.append({"datetime": dt, "timestamp": ts, "app": label, "rx": rx, "tx": tx})

        if traffic_rows:
            df_tr = pd.DataFrame(traffic_rows)
            # Compute delta traffic per app between consecutive samples
            df_tr = df_tr.sort_values(["app", "timestamp"])
            df_tr["total"] = df_tr["rx"] + df_tr["tx"]
            df_tr["delta"] = df_tr.groupby("app")["total"].diff().fillna(0).clip(lower=0)
            df_tr["delta_mb"] = df_tr["delta"] / (1024 * 1024)

            # Pick top apps by total traffic
            app_totals = df_tr.groupby("app")["delta"].sum().sort_values(ascending=False)
            # Filter to apps with >1KB total delta
            top_apps = [a for a in app_totals.index if app_totals[a] > 1024][:8]

            if top_apps:
                # Build foreground periods per app (across all displays)
                fg_periods = defaultdict(list)  # app -> list of (start, end, display_id)
                df_fc = df_focus_lc.copy().sort_values("timestamp")
                df_fc["pkg"] = df_fc.apply(
                    lambda r: str(r.get("current.package", "") or ""), axis=1
                )
                df_fc["did"] = df_fc.apply(
                    lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0,
                    axis=1,
                )
                # For each display, compute foreground intervals
                for did in df_fc["did"].unique():
                    if did >= 10:
                        continue
                    df_d = df_fc[df_fc["did"] == did].reset_index(drop=True)
                    for i in range(len(df_d)):
                        pkg = df_d.iloc[i]["pkg"]
                        start = df_d.iloc[i]["datetime"]
                        end = df_d.iloc[i + 1]["datetime"] if i + 1 < len(df_d) else df_tr["datetime"].max()
                        fg_periods[pkg].append((start, end, did))

                n_apps = len(top_apps)
                fig = make_subplots(
                    rows=n_apps, cols=1, shared_xaxes=True,
                    subplot_titles=[_short_name(a) for a in top_apps],
                    vertical_spacing=0.03,
                )

                for idx, app in enumerate(top_apps, 1):
                    app_df = df_tr[df_tr["app"] == app]
                    # Traffic rate line
                    fig.add_trace(
                        go.Scatter(
                            x=app_df["datetime"], y=app_df["delta_mb"],
                            mode="lines", name=f"{_short_name(app)} traffic",
                            line=dict(color="#e74c3c", width=2),
                            showlegend=(idx == 1),
                            legendgroup="traffic",
                        ),
                        row=idx, col=1,
                    )
                    # Foreground shading
                    periods = fg_periods.get(app, [])
                    added_legend = set()
                    for start, end, did in periods:
                        color = DISPLAY_COLORS.get(did, "#888888")
                        dname = DISPLAY_NAMES.get(did, f"Display {did}")
                        show_legend = (idx == 1 and did not in added_legend)
                        added_legend.add(did)
                        fig.add_trace(
                            go.Scatter(
                                x=[start, end, end, start, start],
                                y=[0, 0, app_df["delta_mb"].max() * 1.1 if not app_df.empty else 1,
                                   app_df["delta_mb"].max() * 1.1 if not app_df.empty else 1, 0],
                                fill="toself", fillcolor=color, opacity=0.15,
                                line=dict(width=0), mode="lines",
                                name=f"Foreground — {dname}",
                                showlegend=show_legend,
                                legendgroup=f"fg_{did}",
                            ),
                            row=idx, col=1,
                        )
                    fig.update_yaxes(title_text="MB", row=idx, col=1)

                fig.update_layout(
                    title="App Lifecycle vs Network Traffic (shaded = foreground)",
                    height=200 * n_apps + 100,
                    legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="center", x=0.5),
                )
                figs.append(fig)

    if not figs:
        return latency_html + "<p>Insufficient cross-collector data for correlations.</p>"
    return latency_html + _figs_to_html(figs)


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
h4 {{ color: #a0a0c0; margin: 15px 0 8px; }}
p {{ margin: 10px 0; line-height: 1.5; }}
em {{ color: #8888a8; }}
.stats-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
               gap: 16px; margin-bottom: 30px; }}
.stat-card {{ background: #16213e; border-radius: 12px; padding: 20px; text-align: center; }}
.stat-card h3 {{ color: #0f9b8e; font-size: 28px; margin-bottom: 4px; }}
.stat-card p {{ color: #8888a8; font-size: 13px; }}
.js-plotly-plot {{ margin-bottom: 20px; }}
.generated {{ text-align: center; color: #555; font-size: 12px; margin-top: 40px; }}
ul {{ margin: 10px 0 10px 20px; }}
li {{ margin: 4px 0; }}
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
        print("Example: python3 generate_report.py telemetry_analysis/telemetry-logs/")
        sys.exit(1)

    paths = sys.argv[1:]
    print(f"[1/5] Ingesting from: {paths}")
    events = ingest(paths)
    print(f"       -> {len(events)} events loaded")

    if not events:
        print("ERROR: No events found. Check the input path.")
        sys.exit(1)

    print("[2/5] Normalizing data...")
    dfs = normalize(events)
    print(f"       -> {len(dfs)} action types")

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
    print(f"\n Done! Report: {output_path} ({size_mb:.1f} MB)")
    print(f"  Open: file://{output_path.resolve()}")


if __name__ == "__main__":
    main()
