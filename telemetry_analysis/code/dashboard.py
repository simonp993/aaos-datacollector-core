#!/usr/bin/env python3
"""
Telemetry Dashboard — Interactive Plotly Dash app for AAOS DataCollector telemetry.

Usage:
    python3 dashboard.py <path_to_jsonl_dir_or_files...>
    # Opens at http://127.0.0.1:8050
"""

import json
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from plotly.subplots import make_subplots

from dash import Dash, Input, Output, State, callback, dcc, html, ALL, ctx, Patch, no_update

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DISPLAY_NAMES = {
    0: "Center Screen",
    2: "Instrument Cluster",
    3: "Passenger Screen",
    4: "Fond / Rear",
    5: "Virtual",
}

DISPLAY_COLORS = {
    0: "#0f9b8e",
    2: "#f39c12",
    3: "#5b8def",
    4: "#9b59b6",
    5: "#888888",
}

DISPLAY_RESOLUTIONS = {
    0: (1920, 720),
    2: (1920, 1080),
    3: (1920, 720),
    4: (1280, 768),
}

TOUCH_EXCLUDED_DISPLAYS = {2, 5}

# Timezone offset: car records in UTC but represents Berlin local time (UTC+2 CEST)
TZ_OFFSET = pd.Timedelta(hours=2)

# ── Porsche Design System Tokens (Light Theme) ──────────────────────────────
# Font: Porsche Next via CDN | Colors: PDS light-theme palette
PDS_FONT_CDN = "https://cdn.ui.porsche.com/porsche-design-system/style/v4/font-face.min.css"
PDS_CREST_URL = "https://cdn.ui.porsche.com/porsche-design-system/crest/porsche-crest.2245c45@2x.webp"
PDS_FONT_FAMILY = "'Porsche Next', 'Arial Narrow', Arial, 'Heiti SC', SimHei, sans-serif"

# PDS Colors (light theme)
PDS_PRIMARY = "#000000"          # foreground / text
PDS_BACKGROUND = "#FFFFFF"       # page surface
PDS_BACKGROUND_SURFACE = "#F2F2F2"  # card / elevated surface
PDS_BACKGROUND_FROSTED = "#EEEFF2"  # subtle secondary surface
PDS_BRAND = "#D5001C"            # Porsche red (accent)
PDS_CONTRAST_LOW = "#535353"     # secondary text
PDS_CONTRAST_MEDIUM = "#323232" # labels
PDS_BORDER = "#C9CACB"          # borders / dividers
PDS_STATE_SUCCESS = "#018A16"
PDS_STATE_WARNING = "#FF9B00"
PDS_STATE_ERROR = "#E00000"

PLOT_TEMPLATE = "plotly_white"

# Standardized legend placement: horizontal, between title and plot area
LEGEND_STYLE = dict(orientation="h", y=1.02, yanchor="bottom", x=0, font=dict(size=9))

# Network transport colors — used wherever transport type is shown
TRANSPORT_COLORS = {
    "ethernet": "#f39c12",
    "wifi": "#0f9b8e",
    "mobile": "#e74c3c",
    "bluetooth": "#5b8def",
    "other": "#888888",
}

CARD_STYLE = {
    "backgroundColor": PDS_BACKGROUND,
    "borderRadius": "4px",
    "padding": "16px 24px",
    "textAlign": "center",
    "minWidth": "150px",
    "flex": "1",
    "boxShadow": "0 2px 4px rgba(0,0,0,0.06)",
    "border": f"1px solid {PDS_BORDER}",
}
CARD_VALUE_STYLE = {"color": PDS_PRIMARY, "fontSize": "28px", "fontWeight": "700", "margin": "0"}
CARD_LABEL_STYLE = {"color": PDS_CONTRAST_LOW, "fontSize": "12px", "margin": "4px 0 0 0"}


# ---------------------------------------------------------------------------
# Data Loading (reused from generate_report.py)
# ---------------------------------------------------------------------------


def _flatten_metadata(meta: dict, prefix: str = "") -> dict:
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


def ingest(paths: list[str]) -> list[dict]:
    events = []
    for path in paths:
        p = Path(path)
        files = sorted(p.glob("*.jsonl")) if p.is_dir() else [p]
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


def normalize(events: list[dict]) -> dict[str, pd.DataFrame]:
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
            df["datetime"] = pd.to_datetime(df["timestamp"], unit="ms") + TZ_OFFSET
            df = df.sort_values("datetime").reset_index(drop=True)
        dfs[action] = df
    return dfs


def expand_samples(df: pd.DataFrame) -> pd.DataFrame:
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
        result["datetime"] = pd.to_datetime(result["timestampMillis"], unit="ms") + TZ_OFFSET
        result = result.sort_values("datetime").reset_index(drop=True)
    elif "gpsTimeMillis" in result.columns:
        result["datetime"] = pd.to_datetime(result["gpsTimeMillis"], unit="ms") + TZ_OFFSET
        result = result.sort_values("datetime").reset_index(drop=True)
    elif "ts" in result.columns:
        result["datetime"] = pd.to_datetime(result["ts"], unit="ms") + TZ_OFFSET
        result = result.sort_values("datetime").reset_index(drop=True)
    return result


def _short_name(pkg: str) -> str:
    if not pkg or pkg == "nan" or pkg == "None":
        return "unknown"
    return pkg


def _get_memory_df(dfs: dict) -> pd.DataFrame:
    """Get memory data from either new System_Memory or legacy Memory_Usage format."""
    # New format: System_Memory with samples [ts, totalMb, availableMb, usedMb, thresholdMb, lowMemory]
    df_sys = dfs.get("System_Memory", pd.DataFrame())
    if not df_sys.empty:
        expanded = expand_samples(df_sys)
        if "availableMb" in expanded.columns:
            expanded = expanded.rename(columns={"availableMb": "availMb", "totalMb": "totalMem_mb"})
            if "totalMem_mb" in expanded.columns:
                expanded["totalMem"] = pd.to_numeric(expanded["totalMem_mb"], errors="coerce") * 1024 * 1024 * 1000
            return expanded
    # Legacy format: Memory_Usage with sampleSchema [timestampMillis, totalMem, availMb, ...]
    df_legacy = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_legacy.empty:
        return expand_samples(df_legacy)
    return pd.DataFrame()


def _get_cpu_df(dfs: dict) -> pd.DataFrame:
    """Get CPU data from either new System_CpuLoad or legacy CPU_Usage format."""
    # New format: System_CpuLoad with samples [ts, ownCpuPct]
    df_sys = dfs.get("System_CpuLoad", pd.DataFrame())
    if not df_sys.empty:
        expanded = expand_samples(df_sys)
        if "ownCpuPct" in expanded.columns:
            expanded = expanded.rename(columns={"ownCpuPct": "usagePct"})
            if "ts" in expanded.columns and "datetime" not in expanded.columns:
                expanded["datetime"] = pd.to_datetime(expanded["ts"], unit="ms") + TZ_OFFSET
            return expanded
    # Legacy format: CPU_Usage
    df_legacy = dfs.get("CPU_Usage", pd.DataFrame())
    if not df_legacy.empty:
        return expand_samples(df_legacy)
    return pd.DataFrame()


# ---------------------------------------------------------------------------
# Load data at startup
# ---------------------------------------------------------------------------

DATA_PATHS = sys.argv[1:] if len(sys.argv) > 1 else ["telemetry_analysis/telemetry-logs/"]
print(f"[Dashboard] Loading data from: {DATA_PATHS}")
RAW_EVENTS = ingest(DATA_PATHS)
DFS = normalize(RAW_EVENTS)
print(f"[Dashboard] {len(RAW_EVENTS)} events, {len(DFS)} action types")

# Pre-compute some globals
ALL_TIMESTAMPS = [e.get("timestamp") for e in RAW_EVENTS if e.get("timestamp")]
MIN_TS = min(ALL_TIMESTAMPS) if ALL_TIMESTAMPS else 0
MAX_TS = max(ALL_TIMESTAMPS) if ALL_TIMESTAMPS else 0
MIN_DT = pd.to_datetime(MIN_TS, unit="ms") + TZ_OFFSET
MAX_DT = pd.to_datetime(MAX_TS, unit="ms") + TZ_OFFSET

# Extract all apps from focus data
df_focus_global = DFS.get("App_FocusChanged", pd.DataFrame())
ALL_APPS = []
if not df_focus_global.empty:
    pkgs = df_focus_global.apply(
        lambda r: str(r.get("current.package", "") or ""), axis=1
    )
    ALL_APPS = sorted(set(p for p in pkgs if p and p != "nan"))

# Global app color map — ensures same app always gets same color across all tabs
_APP_PALETTE = px.colors.qualitative.Plotly + px.colors.qualitative.Set2 + px.colors.qualitative.Pastel
APP_COLOR_MAP = {pkg: _APP_PALETTE[i % len(_APP_PALETTE)] for i, pkg in enumerate(ALL_APPS)}

ALL_DISPLAYS = sorted(
    set(
        int(r.get("current.displayId") or 0)
        for _, r in df_focus_global.iterrows()
        if pd.notna(r.get("current.displayId"))
    )
) if not df_focus_global.empty else []

# Extract all trigger types
ALL_TRIGGERS = sorted(set(
    e.get("payload", {}).get("trigger", "") for e in RAW_EVENTS
    if e.get("payload", {}).get("trigger")
))

# Extract all APNs and network transport types from Network_PerAppTraffic
_df_perapp_global = DFS.get("Network_PerAppTraffic", pd.DataFrame())
ALL_APNS: list[str] = []
ALL_TRANSPORTS: list[str] = []
if not _df_perapp_global.empty and "apps" in _df_perapp_global.columns:
    _apn_set: set[str] = set()
    _transport_set: set[str] = set()
    for _, _row in _df_perapp_global.iterrows():
        _apps_data = _row.get("apps", [])
        _schema = _row.get("schema")
        for _a in _apps_data:
            if isinstance(_a, list) and _schema:
                _rec = dict(zip(_schema, _a))
            elif isinstance(_a, dict):
                _rec = _a
            else:
                continue
            _apn_set.add(_rec.get("apn", "unknown"))
            _transport_set.add(_rec.get("networkType", "unknown"))
    ALL_APNS = sorted(_apn_set)
    ALL_TRANSPORTS = sorted(_transport_set)

# Extract all navigation focus owners
_df_nav_global = DFS.get("Navigation_FocusChanged", pd.DataFrame())
ALL_NAV_OWNERS: list[str] = []
if not _df_nav_global.empty and "currentOwner" in _df_nav_global.columns:
    ALL_NAV_OWNERS = sorted(
        str(x) for x in _df_nav_global["currentOwner"].dropna().unique() if str(x) != "nan"
    )

# Compute session time ranges for the session indicator bar
_session_events = defaultdict(list)
for _e in RAW_EVENTS:
    _s = _e.get("_session")
    _t = _e.get("timestamp")
    if _s and _t:
        _session_events[_s].append(_t)
SESSION_RANGES = sorted(
    [(min(ts_list), max(ts_list)) for ts_list in _session_events.values()],
    key=lambda x: x[0],
)

# Pre-compute datetime session ranges for gap detection
SESSION_DT_RANGES = [
    (pd.to_datetime(s, unit="ms") + TZ_OFFSET, pd.to_datetime(e, unit="ms") + TZ_OFFSET)
    for s, e in SESSION_RANGES
]


def _session_end_for(dt):
    """Return the end of the session containing *dt*, or *dt* itself."""
    for s_dt, e_dt in SESSION_DT_RANGES:
        if s_dt <= dt <= e_dt:
            return e_dt
    return dt


def _compute_active_periods() -> list[tuple]:
    """Compute active (non-suspended) time periods from Power_StateChanged events.
    Returns list of (start_dt, end_dt) tuples where the system was ON."""
    df_power = DFS.get("Power_StateChanged", pd.DataFrame())
    if df_power.empty or "datetime" not in df_power.columns:
        # No power data — assume always active across sessions
        return list(SESSION_DT_RANGES) if SESSION_DT_RANGES else [(MIN_DT, MAX_DT)]

    periods = []
    current_start = MIN_DT
    for _, row in df_power.sort_values("datetime").iterrows():
        state = str(row.get("state", "")).upper()
        dt = row["datetime"]
        if state in ("SUSPEND_ENTER", "SHUTDOWN_ENTER"):
            # End of an active period
            if current_start is not None:
                periods.append((current_start, dt))
                current_start = None
        elif state in ("SUSPEND_EXIT", "ON") and current_start is None:
            current_start = dt
    # Close final period
    if current_start is not None:
        periods.append((current_start, MAX_DT))
    return periods if periods else [(MIN_DT, MAX_DT)]


ACTIVE_PERIODS = _compute_active_periods()


def _clamp_to_active(start_dt, end_dt):
    """Clamp a time range to the active periods. Returns end clamped to the
    end of the active period containing start_dt, or start_dt if in a gap."""
    for ap_start, ap_end in ACTIVE_PERIODS:
        if ap_start <= start_dt <= ap_end:
            return min(end_dt, ap_end)
    return start_dt  # In a gap — zero-width


def _build_range_selector_fig():
    """Build a plotly figure with session bars and a built-in rangeslider for time selection."""
    fig = go.Figure()
    # Add session bars as visual indicators in the main area and rangeslider
    for i, (s, e) in enumerate(SESSION_RANGES):
        s_dt = pd.to_datetime(s, unit="ms") + TZ_OFFSET
        e_dt = pd.to_datetime(e, unit="ms") + TZ_OFFSET
        fig.add_trace(go.Bar(
            x=[s_dt + (e_dt - s_dt) / 2],
            y=[1],
            width=[(e_dt - s_dt).total_seconds() * 1000],
            marker_color=PDS_BRAND,
            opacity=0.6,
            showlegend=False,
            hoverinfo="text",
            text=[f"Session {i + 1}: {s_dt.strftime('%H:%M:%S')} — {e_dt.strftime('%H:%M:%S')}"],
        ))
    min_dt = pd.to_datetime(MIN_TS, unit="ms") + TZ_OFFSET
    max_dt = pd.to_datetime(MAX_TS, unit="ms") + TZ_OFFSET
    fig.update_layout(
        xaxis=dict(
            range=[min_dt, max_dt],
            type="date",
            rangeslider=dict(visible=True, thickness=0.3),
            showgrid=False,
        ),
        yaxis=dict(range=[0, 1], showticklabels=False, showgrid=False, zeroline=False,
                   fixedrange=True),
        height=120,
        margin=dict(l=10, r=10, t=5, b=5),
        paper_bgcolor="rgba(0,0,0,0)",
        plot_bgcolor="rgba(250,250,250,0.5)",
        bargap=0,
    )
    return fig


# ---------------------------------------------------------------------------
# Dash App
# ---------------------------------------------------------------------------

app = Dash(__name__, suppress_callback_exceptions=True)
app.title = "Porsche AAOS Telemetry"

# Porsche Design System styling via index_string
app.index_string = '''<!DOCTYPE html>
<html>
<head>
{%metas%}
<title>{%title%}</title>
{%favicon%}
{%css%}
<link rel="stylesheet" href="''' + PDS_FONT_CDN + '''">
<style>
body { font-family: ''' + PDS_FONT_FAMILY + '''; }
.rc-slider-track { background-color: #D5001C !important; }
.rc-slider-handle { border-color: #D5001C !important; }
.rc-slider-handle:hover, .rc-slider-handle:active { border-color: #a50016 !important; box-shadow: 0 0 5px rgba(213,0,28,0.3) !important; }
.rc-slider-dot-active { border-color: #D5001C !important; }
.Select-control, .Select-menu-outer { font-family: ''' + PDS_FONT_FAMILY + '''; }
.dash-dropdown .Select-control { border-color: #C9CACB !important; border-radius: 4px !important; }
.dash-dropdown .Select-control:hover { border-color: #000 !important; }
.tab--selected { border-color: #D5001C !important; color: #000 !important; font-weight: 600 !important; }
</style>
</head>
<body>
{%app_entry%}
<footer>
{%config%}
{%scripts%}
{%renderer%}
</footer>
</body>
</html>'''

app.layout = html.Div(
    style={"backgroundColor": PDS_BACKGROUND_SURFACE, "minHeight": "100vh", "padding": "20px 30px",
           "fontFamily": PDS_FONT_FAMILY, "color": PDS_PRIMARY},
    children=[
        # Header — Porsche crest + wordmark
        html.Div(
            style={"display": "flex", "alignItems": "center", "justifyContent": "space-between",
                   "marginBottom": "20px", "paddingBottom": "16px",
                   "borderBottom": f"1px solid {PDS_BORDER}"},
            children=[
                html.Div(
                    style={"display": "flex", "alignItems": "center", "gap": "12px"},
                    children=[
                        html.Img(src=PDS_CREST_URL, style={"height": "40px", "width": "auto"}),
                        html.Div([
                            html.Span("PORSCHE",
                                      style={"fontSize": "11px", "letterSpacing": "2px",
                                             "fontWeight": "600", "color": PDS_PRIMARY,
                                             "display": "block"}),
                            html.Span("AAOS Telemetry",
                                      style={"fontSize": "18px", "fontWeight": "700",
                                             "color": PDS_PRIMARY, "lineHeight": "1.2"}),
                        ]),
                    ],
                ),
                html.Span(f"Data: {MIN_DT.strftime('%Y-%m-%d %H:%M')} — {MAX_DT.strftime('%H:%M')}",
                           style={"color": PDS_CONTRAST_LOW, "fontSize": "13px"}),
            ],
        ),

        # Filters area
        html.Div(
            style={"marginBottom": "24px", "backgroundColor": PDS_BACKGROUND, "padding": "16px 20px",
                   "borderRadius": "4px", "border": f"1px solid {PDS_BORDER}"},
            children=[
                # Row 1: Data range label
                html.Div(
                    style={"display": "flex", "gap": "12px", "alignItems": "center",
                           "marginBottom": "8px"},
                    children=[
                        html.Span(
                            f"Data: {MIN_DT.strftime('%Y-%m-%d %H:%M')} — {MAX_DT.strftime('%H:%M')}",
                            style={"fontSize": "13px", "color": PDS_CONTRAST_LOW, "fontWeight": "600"},
                        ),
                        html.Span(
                            id="time-range-label",
                            style={"fontSize": "12px", "color": PDS_CONTRAST_MEDIUM, "marginLeft": "auto"},
                        ),
                    ],
                ),
                # Row 2: Plotly rangeslider with session indicators
                html.Div([
                    dcc.Graph(
                        id="range-selector",
                        figure=_build_range_selector_fig(),
                        config={"displayModeBar": False},
                        style={"height": "120px"},
                    ),
                ], style={"marginBottom": "8px"}),
                # Row 3: Dropdowns
                html.Div(
                    style={"display": "flex", "gap": "16px", "alignItems": "flex-end", "flexWrap": "wrap"},
                    children=[
                        html.Div([
                            html.Label("Displays", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                           "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="display-filter",
                                options=[{"label": DISPLAY_NAMES.get(d, f"Display {d}"), "value": d}
                                         for d in ALL_DISPLAYS],
                                value=ALL_DISPLAYS, multi=True, placeholder="All displays",
                                style={"minWidth": "200px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Apps", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                       "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="app-filter",
                                options=[{"label": _short_name(a), "value": a} for a in ALL_APPS],
                                value=[], multi=True, placeholder="All apps",
                                style={"minWidth": "200px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Trigger", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                          "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="trigger-filter",
                                options=[{"label": t, "value": t} for t in ALL_TRIGGERS],
                                value=[], multi=True, placeholder="All triggers",
                                style={"minWidth": "150px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("APN", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                      "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="apn-filter",
                                options=[{"label": a, "value": a} for a in ALL_APNS],
                                value=[], multi=True, placeholder="All APNs",
                                style={"minWidth": "150px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Transport", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                            "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="transport-filter",
                                options=[{"label": t, "value": t} for t in ALL_TRANSPORTS],
                                value=[], multi=True, placeholder="All transports",
                                style={"minWidth": "150px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Nav Focus", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                            "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="nav-focus-filter",
                                options=[{"label": _short_name(o), "value": o} for o in ALL_NAV_OWNERS],
                                value=[], multi=True, placeholder="All nav owners",
                                style={"minWidth": "150px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Foreground App", style={"fontSize": "11px", "color": PDS_CONTRAST_LOW,
                                                                 "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="foreground-filter",
                                options=[{"label": _short_name(a), "value": a} for a in ALL_APPS],
                                value=[], multi=True, placeholder="All apps",
                                style={"minWidth": "180px"},
                            ),
                        ]),
                    ],
                ),
            ],
        ),

        # KPI Cards row
        html.Div(id="kpi-cards", style={"display": "flex", "gap": "16px", "marginBottom": "24px", "flexWrap": "wrap"}),

        # Tabs
        dcc.Tabs(
            id="section-tabs", value="timeline",
            colors={"border": PDS_BORDER, "primary": PDS_BRAND, "background": PDS_BACKGROUND},
            style={"marginBottom": "20px"},
            children=[
                dcc.Tab(label="Timeline", value="timeline",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="App Usage", value="app_usage",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Touch", value="touch",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Performance", value="performance",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Network", value="network",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Vehicle & GPS", value="vehicle",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Audio & Media", value="audio_media",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Display & Power", value="display_power",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
                dcc.Tab(label="Collector Stats", value="overview",
                        style={"backgroundColor": PDS_BACKGROUND, "color": PDS_CONTRAST_LOW, "padding": "8px 16px", "border": f"1px solid {PDS_BORDER}"},
                        selected_style={"backgroundColor": PDS_BACKGROUND, "color": PDS_PRIMARY, "padding": "8px 16px", "borderBottom": f"2px solid {PDS_BRAND}", "fontWeight": "600"}),
            ],
        ),

        # Chart content
        html.Div(id="tab-content", style={"minHeight": "600px"}),

        # Hidden store for global graph zoom synchronization
        dcc.Store(id="graph-zoom", data=None),
    ],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _filter_events(time_range):
    """Filter events based on timestamp range (actual ms values from slider)."""
    if time_range == [MIN_TS, MAX_TS]:
        return RAW_EVENTS, DFS
    start_ts, end_ts = time_range
    filtered = [e for e in RAW_EVENTS if start_ts <= (e.get("timestamp") or 0) <= end_ts]
    return filtered, normalize(filtered)


def _make_kpi_card(value, label):
    return html.Div(style=CARD_STYLE, children=[
        html.P(str(value), style=CARD_VALUE_STYLE),
        html.P(label, style=CARD_LABEL_STYLE),
    ])


def _apply_theme(fig, height=400):
    fig.update_layout(
        template=PLOT_TEMPLATE,
        paper_bgcolor="#ffffff",
        plot_bgcolor="#fafafa",
        font_color="#111111",
        height=height,
        margin=dict(l=50, r=30, t=80, b=40),
        legend=LEGEND_STYLE,
    )
    return fig




def _graph(fig, height=400):
    _apply_theme(fig, height)
    return dcc.Graph(figure=fig, config={"displayModeBar": True, "displaylogo": False})


# Synced-graph counter — all time-based graphs use a pattern-matching ID
_synced_counter: list[int] = [0]


def _synced_graph(fig, height=400, zoom_range=None):
    """Graph with a pattern-matching ID so zoom syncs across all tabs."""
    _apply_theme(fig, height)
    _apply_zoom(fig, zoom_range)
    idx = _synced_counter[0]
    _synced_counter[0] += 1
    gid = {"type": "synced-graph", "index": idx}
    return dcc.Graph(id=gid, figure=fig, config={"displayModeBar": True, "displaylogo": False})


def _apply_zoom(fig, zoom_range):
    """Apply a zoom override to a figure's primary x-axis if set."""
    if zoom_range:
        fig.update_layout(xaxis=dict(range=zoom_range, autorange=False))


# Consistent margins for timeline charts so x-axes align
_TIMELINE_MARGIN = dict(l=120, r=60, t=90, b=30)

# Counter for assigning unique IDs to timeline graphs within a render
_timeline_ids: list[str] = []


def _timeline_graph(fig, height=400, zoom_range=None):
    _apply_theme(fig, height)
    fig.update_layout(margin=_TIMELINE_MARGIN)
    _apply_zoom(fig, zoom_range)
    idx = _synced_counter[0]
    _synced_counter[0] += 1
    gid = {"type": "synced-graph", "index": idx}
    return dcc.Graph(id=gid, figure=fig, config={"displayModeBar": True, "displaylogo": False})


def _row(*children):
    return html.Div(style={"display": "grid", "gridTemplateColumns": "1fr 1fr", "gap": "20px",
                           "marginBottom": "20px"}, children=list(children))


def _full(*children):
    return html.Div(style={"marginBottom": "20px"}, children=list(children))


# ---------------------------------------------------------------------------
# Filter helpers
# ---------------------------------------------------------------------------


def _compute_focus_periods(df, column, selected_values):
    """Compute time periods (as (start_ts, end_ts) tuples) where column had one of selected_values.

    Uses the state-change pattern: an entry is active from its timestamp until the next entry
    in the same dataframe.
    """
    periods = []
    df_sorted = df.sort_values("timestamp").reset_index(drop=True)
    for i, row in df_sorted.iterrows():
        val = str(row.get(column, ""))
        ts = row.get("timestamp")
        if val in selected_values:
            # Find end: next event in the dataframe
            if i + 1 < len(df_sorted):
                end_ts = df_sorted.iloc[i + 1]["timestamp"]
            else:
                end_ts = MAX_TS
            periods.append((ts, end_ts))
    return periods


def _filter_events_by_periods(events, periods):
    """Keep only events whose timestamp falls within any of the given (start, end) periods."""
    if not periods:
        return events
    filtered = []
    for e in events:
        ts = e.get("timestamp", 0)
        for p_start, p_end in periods:
            if p_start <= ts <= p_end:
                filtered.append(e)
                break
    return filtered


# ---------------------------------------------------------------------------
# Callbacks
# ---------------------------------------------------------------------------


@callback(
    Output("kpi-cards", "children"),
    Output("tab-content", "children"),
    Output("time-range-label", "children"),
    Input("range-selector", "relayoutData"),
    Input("display-filter", "value"),
    Input("app-filter", "value"),
    Input("trigger-filter", "value"),
    Input("apn-filter", "value"),
    Input("transport-filter", "value"),
    Input("nav-focus-filter", "value"),
    Input("foreground-filter", "value"),
    Input("section-tabs", "value"),
    Input("graph-zoom", "data"),
)
def update_dashboard(relayout_data, displays, apps, triggers, apn_filter, transport_filter,
                     nav_focus_filter, foreground_filter, active_tab, zoom_data):
    # Determine time range from the rangeslider
    start_ts, end_ts = MIN_TS, MAX_TS
    if relayout_data:
        # rangeslider emits xaxis.range[0] / xaxis.range[1] on drag,
        # or xaxis.range as a list on some interactions,
        # or autosize / xaxis.autorange on double-click reset.
        x_start = relayout_data.get("xaxis.range[0]")
        x_end = relayout_data.get("xaxis.range[1]")
        if not x_start:
            rng = relayout_data.get("xaxis.range")
            if isinstance(rng, list) and len(rng) >= 2:
                x_start, x_end = rng[0], rng[1]
        if x_start and x_end:
            try:
                start_ts = int(pd.to_datetime(x_start).timestamp() * 1000)
                end_ts = int(pd.to_datetime(x_end).timestamp() * 1000)
            except Exception:
                pass

    start_ts = max(MIN_TS, min(start_ts, MAX_TS))
    end_ts = max(MIN_TS, min(end_ts, MAX_TS))

    # Build range label
    s_dt = pd.to_datetime(start_ts, unit="ms") + TZ_OFFSET
    e_dt = pd.to_datetime(end_ts, unit="ms") + TZ_OFFSET
    range_label = f"Selected: {s_dt.strftime('%H:%M:%S')} — {e_dt.strftime('%H:%M:%S')}"

    events, dfs = _filter_events([start_ts, end_ts])
    displays = displays or ALL_DISPLAYS
    apps = apps or []
    triggers = triggers or []
    apn_filter = apn_filter or []
    transport_filter = transport_filter or []
    nav_focus_filter = nav_focus_filter or []
    foreground_filter = foreground_filter or []

    # Apply trigger filter
    if triggers:
        events = [e for e in events if e.get("payload", {}).get("trigger", "") in triggers]
        dfs = normalize(events)

    # Apply APN/Transport filter: restrict to timestamps where selected APN/transport had traffic
    if apn_filter or transport_filter:
        df_traffic = dfs.get("Network_PerAppTraffic", pd.DataFrame())
        if not df_traffic.empty and "apps" in df_traffic.columns:
            valid_ts = set()
            for _, row in df_traffic.iterrows():
                apps_data = row.get("apps", [])
                schema = row.get("schema")
                ts = row.get("timestamp")
                for a in apps_data:
                    if isinstance(a, list) and schema:
                        rec = dict(zip(schema, a))
                    elif isinstance(a, dict):
                        rec = a
                    else:
                        continue
                    apn_ok = (not apn_filter) or rec.get("apn", "unknown") in apn_filter
                    transport_ok = (not transport_filter) or rec.get("networkType", "unknown") in transport_filter
                    if apn_ok and transport_ok:
                        valid_ts.add(ts)
                        break
            if valid_ts:
                # Keep events within ±30s of any valid traffic timestamp
                valid_ts_sorted = sorted(valid_ts)
                events = [e for e in events if any(
                    abs(e.get("timestamp", 0) - vt) < 30000 for vt in valid_ts_sorted
                )]
                dfs = normalize(events)

    # Apply nav focus filter: keep only data from periods when selected app had nav focus
    if nav_focus_filter:
        df_nav_f = dfs.get("Navigation_FocusChanged", pd.DataFrame())
        if not df_nav_f.empty and "currentOwner" in df_nav_f.columns:
            nav_periods = _compute_focus_periods(df_nav_f, "currentOwner", nav_focus_filter)
            if nav_periods:
                events = _filter_events_by_periods(events, nav_periods)
                dfs = normalize(events)

    # Apply foreground app filter: keep only data from periods when selected app was in foreground
    if foreground_filter:
        df_fg = dfs.get("App_FocusChanged", pd.DataFrame())
        if not df_fg.empty and "current.package" in df_fg.columns:
            fg_periods = _compute_focus_periods(df_fg, "current.package", foreground_filter)
            if fg_periods:
                events = _filter_events_by_periods(events, fg_periods)
                dfs = normalize(events)

    # KPI cards
    total_events = len(events)
    duration_ms = (max(e["timestamp"] for e in events) - min(e["timestamp"] for e in events)) if events else 0
    duration_min = duration_ms / 60000
    n_collectors = len(set(e.get("signalId", "").split(".")[-1] for e in events))
    n_sessions = len(set(e.get("_session") for e in events))

    # Touch count (each Touch_Down = one interaction)
    touch_count = len(dfs.get("Touch_Down", pd.DataFrame()))
    # App switches
    df_focus = dfs.get("App_FocusChanged", pd.DataFrame())
    app_switches = len(df_focus)

    cards = [
        _make_kpi_card(f"{total_events:,}", "Total Events"),
        _make_kpi_card(f"{duration_min:.1f} min", "Recording Duration"),
        _make_kpi_card(n_collectors, "Active Collectors"),
        _make_kpi_card(n_sessions, "Sessions"),
        _make_kpi_card(touch_count, "Touch Events"),
        _make_kpi_card(app_switches, "App Switches"),
    ]

    # Reset synced graph counter for this render
    _synced_counter[0] = 0

    # Parse zoom override from graph-zoom store
    zoom_range = None
    if zoom_data and isinstance(zoom_data, list) and len(zoom_data) == 2:
        zoom_range = zoom_data

    # Tab content
    if active_tab == "timeline":
        content = _build_timeline_tab(events, dfs, displays, zoom_range)
    elif active_tab == "overview":
        content = _build_overview_tab(events, dfs, zoom_range)
    elif active_tab == "app_usage":
        content = _build_app_usage_tab(dfs, displays, apps, zoom_range)
    elif active_tab == "touch":
        content = _build_touch_tab(dfs, displays, zoom_range)
    elif active_tab == "performance":
        content = _build_performance_tab(dfs, zoom_range)
    elif active_tab == "network":
        content = _build_network_tab(dfs, apps, apn_filter, transport_filter, zoom_range)
    elif active_tab == "vehicle":
        content = _build_vehicle_tab(dfs, zoom_range)
    elif active_tab == "audio_media":
        content = _build_audio_media_tab(dfs, zoom_range)
    elif active_tab == "display_power":
        content = _build_display_power_tab(dfs, zoom_range)
    else:
        content = html.P("Select a tab")

    return cards, content, range_label


# Sync zoom: any synced graph zoom updates the global store
@callback(
    Output("graph-zoom", "data"),
    Input({"type": "synced-graph", "index": ALL}, "relayoutData"),
    State("graph-zoom", "data"),
    prevent_initial_call=True,
)
def sync_graph_zoom(relayout_list, current_zoom):
    # Guard: no synced graphs exist (e.g. during tab switch)
    if not relayout_list:
        return no_update

    triggered_id = ctx.triggered_id
    if not triggered_id or not isinstance(triggered_id, dict):
        return no_update

    # Find which position in relayout_list corresponds to the triggered graph.
    # ctx.args_grouping[0] is a list of dicts with {"id": {...}, "value": ...}
    # matching the ALL pattern. We must find the entry whose id matches triggered_id.
    relayout = None
    args = ctx.args_grouping
    if not args or not isinstance(args, list) or len(args) == 0:
        return no_update
    group = args[0]
    if not isinstance(group, list):
        return no_update
    for item in group:
        if not isinstance(item, dict):
            continue
        if item.get("id") == triggered_id:
            relayout = item.get("value")
            break

    if not relayout or not isinstance(relayout, dict):
        return no_update

    # Ignore autosize events (fired when graphs are first rendered after tab switch)
    if relayout.get("autosize") or relayout.get("xaxis.autorange"):
        return no_update

    x0 = relayout.get("xaxis.range[0]")
    x1 = relayout.get("xaxis.range[1]")
    if x0 is not None and x1 is not None:
        # If range matches what's already stored, this is a double-click reset
        # (plotly restoring to the baked-in initial range)
        if current_zoom and len(current_zoom) == 2:
            if str(x0) == str(current_zoom[0]) and str(x1) == str(current_zoom[1]):
                return None
        return [x0, x1]

    return no_update


# ---------------------------------------------------------------------------
# Tab Builders
# ---------------------------------------------------------------------------


def _build_timeline_tab(events, dfs, displays, zoom_range=None):
    """Unified stacked timeline: separate figures for app, touches, FPS, memory, network."""
    _timeline_ids.clear()
    children = []
    children.append(html.P(
        "Synchronized timeline — zoom any chart to sync all others.",
        style={"color": "#888", "fontSize": "12px", "marginBottom": "8px"},
    ))

    # Compute shared x-axis range from all events
    all_ts = [e.get("timestamp") for e in events if e.get("timestamp")]
    if all_ts:
        x_min = pd.to_datetime(min(all_ts), unit="ms") + TZ_OFFSET
        x_max = pd.to_datetime(max(all_ts), unit="ms") + TZ_OFFSET
    else:
        x_min, x_max = MIN_DT, MAX_DT

    # --- 1) Foreground App (shape-based Gantt, same as App Usage) ---
    df_focus = dfs.get("App_FocusChanged", pd.DataFrame())
    if not df_focus.empty:
        df_focus = df_focus.copy()
        df_focus["current_pkg"] = df_focus.apply(lambda r: str(r.get("current.package", "") or ""), axis=1)
        df_focus["display_id"] = df_focus.apply(
            lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0, axis=1
        )
        df_focus = df_focus.sort_values("datetime").reset_index(drop=True)
        active_displays = sorted(d for d in df_focus["display_id"].unique() if d in displays)
        display_labels = [DISPLAY_NAMES.get(d, f"D{d}") for d in active_displays]

        fig_app = go.Figure()
        legend_added = set()

        for idx, did in enumerate(active_displays):
            df_d = df_focus[df_focus["display_id"] == did].reset_index(drop=True)
            if df_d.empty:
                continue
            for i in range(len(df_d)):
                pkg = _short_name(str(df_d.iloc[i]["current_pkg"]))
                start = df_d.iloc[i]["datetime"]
                # End is the next event OR active-period boundary, whichever comes first
                if i + 1 < len(df_d):
                    next_dt = df_d.iloc[i + 1]["datetime"]
                    end = _clamp_to_active(start, next_dt)
                else:
                    end = _clamp_to_active(start, _session_end_for(start))
                dur_s = (end - start).total_seconds()
                if 0 < dur_s < 86400:
                    color = APP_COLOR_MAP.get(pkg, "#888")
                    fig_app.add_shape(
                        type="rect", x0=start, x1=end,
                        y0=idx - 0.4, y1=idx + 0.4,
                        fillcolor=color, opacity=0.85,
                        line=dict(width=0.5, color="#fff"),
                    )
                    if pkg not in legend_added:
                        legend_added.add(pkg)
                        fig_app.add_trace(go.Scatter(
                            x=[None], y=[None], mode="markers",
                            marker=dict(size=12, color=color, symbol="square"),
                            name=pkg, showlegend=True,
                        ))
                    if dur_s > 15:
                        mid = start + (end - start) / 2
                        fig_app.add_annotation(
                            x=mid, y=idx, text=pkg,
                            showarrow=False, font=dict(size=7, color="#222"),
                        )
        n_disp = max(len(active_displays), 1)
        fig_app.update_layout(
            title="Foreground App",
            yaxis=dict(
                tickvals=list(range(len(display_labels))),
                ticktext=display_labels,
                range=[-0.5, len(display_labels) - 0.5],
            ),
            xaxis=dict(type="date", range=[x_min, x_max]),
            height=80 * n_disp + 120,
        )
        children.append(_full(_timeline_graph(fig_app, 80 * n_disp + 120, zoom_range)))

    # --- 2) Touch Rate (bars per 5s) — count only Touch_Down (one per interaction) ---
    touch_dfs_list = [dfs.get("Touch_Down", pd.DataFrame())]
    touch_dfs_list = [df for df in touch_dfs_list if not df.empty]
    if touch_dfs_list:
        all_touch = pd.concat(touch_dfs_list, ignore_index=True)
        if "datetime" in all_touch.columns and "displayId" in all_touch.columns:
            fig_touch = go.Figure()
            all_touch["time_bin"] = all_touch["datetime"].dt.floor("5s")
            for did in sorted(displays):
                if did in TOUCH_EXCLUDED_DISPLAYS:
                    continue
                df_d = all_touch[all_touch["displayId"] == did]
                if df_d.empty:
                    continue
                name = DISPLAY_NAMES.get(did, f"Display {did}")
                color = DISPLAY_COLORS.get(did, "#888")
                rate = df_d.groupby("time_bin").size().reset_index(name="count")
                rate["tpm"] = rate["count"] * 12
                fig_touch.add_trace(go.Bar(
                    x=rate["time_bin"], y=rate["tpm"],
                    name=name, marker_color=color, opacity=0.7,
                ))
            fig_touch.update_layout(
                title="Touch Rate (per min)", yaxis_title="Touches/min",
                barmode="overlay", height=250,
                xaxis=dict(range=[x_min, x_max]),
            )
            children.append(_full(_timeline_graph(fig_touch, 250, zoom_range)))

    # --- 3) Dropped Frames ---
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    if not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_dropped = go.Figure()
            fig_dropped.add_trace(go.Bar(
                x=fps_exp["datetime"], y=fps_exp["dropped"],
                name="Dropped Frames", marker_color="rgba(231,76,60,0.85)",
            ))
            fig_dropped.update_layout(
                title="Dropped Frames", yaxis_title="Frames", height=250,
                xaxis=dict(range=[x_min, x_max]),
            )
            children.append(_full(_timeline_graph(fig_dropped, 250, zoom_range)))

    # --- 4) Available Memory ---
    mem_exp = _get_memory_df(dfs)
    if not mem_exp.empty:
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            mem_exp["availGb"] = pd.to_numeric(mem_exp["availMb"], errors="coerce") / 1000
            fig_mem = go.Figure()
            fig_mem.add_trace(go.Scatter(
                x=mem_exp["datetime"], y=mem_exp["availGb"], mode="lines",
                name="Available", line=dict(color="#0f9b8e", width=2),
                fill="tozeroy", fillcolor="rgba(15,155,142,0.1)",
            ))
            # Add total memory as dashed red line
            total_gb = None
            if "totalMem" in mem_exp.columns:
                total_vals = mem_exp["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_gb = float(total_vals.iloc[0]) / (1024 * 1024 * 1000)
            elif "totalMem_mb" in mem_exp.columns:
                total_vals = pd.to_numeric(mem_exp["totalMem_mb"], errors="coerce").dropna()
                if len(total_vals) > 0:
                    total_gb = float(total_vals.iloc[0]) / 1000
            if total_gb:
                fig_mem.add_hline(y=total_gb, line_dash="dash", line_color="red",
                                  annotation_text=f"Total: {total_gb:.1f} GB")
                fig_mem.add_trace(go.Scatter(
                    x=[None], y=[None], mode="lines",
                    line=dict(color="red", dash="dash", width=2),
                    name=f"Total ({total_gb:.1f} GB)", showlegend=True,
                ))
            fig_mem.update_layout(
                title="Available Memory", yaxis_title="GB", height=250,
                xaxis=dict(range=[x_min, x_max]),
            )
            children.append(_full(_timeline_graph(fig_mem, 250, zoom_range)))

    # --- 5) Network Traffic per app (stacked bars per 60s window) ---
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    if not df_perapp.empty and "apps" in df_perapp.columns:
        # Build per-app per-timestamp traffic rows
        app_traffic_rows = []
        for _, row in df_perapp.sort_values("timestamp").iterrows():
            dt = row.get("datetime")
            ts = row.get("timestamp")
            apps_data = row.get("apps", [])
            schema = row.get("schema")
            if not apps_data or not isinstance(apps_data, list):
                continue
            for a in apps_data:
                if isinstance(a, dict):
                    # Legacy format: list of dicts
                    pkgs = a.get("packages", [])
                    uid = a.get("uid", 0)
                    label = _short_name(pkgs[0]) if pkgs else f"uid:{uid}"
                    app_traffic_rows.append({
                        "datetime": dt, "timestamp": ts, "app": label,
                        "rx": a.get("rxBytes", 0), "tx": a.get("txBytes", 0),
                    })
                elif isinstance(a, list) and schema:
                    # New format: list of arrays with schema
                    rec = dict(zip(schema, a))
                    pkgs = rec.get("packages", [])
                    uid = rec.get("uid", 0)
                    label = _short_name(pkgs[0]) if pkgs else f"uid:{uid}"
                    app_traffic_rows.append({
                        "datetime": dt, "timestamp": ts, "app": label,
                        "rx": rec.get("rxBytes", 0), "tx": rec.get("txBytes", 0),
                    })

        if app_traffic_rows:
            df_at = pd.DataFrame(app_traffic_rows).sort_values(["app", "timestamp"])
            df_at["total"] = df_at["rx"] + df_at["tx"]
            df_at["delta"] = df_at.groupby("app")["total"].diff().fillna(0).clip(lower=0)
            df_at["delta_mb"] = df_at["delta"] / (1024 * 1024)
            df_at["time_bin"] = df_at["datetime"].dt.floor("60s")

            # Aggregate per app per 60s bin
            binned = df_at.groupby(["time_bin", "app"])["delta_mb"].sum().reset_index()
            # Top apps by total traffic
            app_totals = binned.groupby("app")["delta_mb"].sum().sort_values(ascending=False)
            top_apps = [a for a in app_totals.index if app_totals[a] > 0.0001][:12]

            if top_apps:
                # Assign colors per app
                net_palette = px.colors.qualitative.Plotly + px.colors.qualitative.Set2
                fig_net = go.Figure()
                for i, app_name in enumerate(top_apps):
                    app_data = binned[binned["app"] == app_name].sort_values("time_bin")
                    color = net_palette[i % len(net_palette)]
                    fig_net.add_trace(go.Bar(
                        x=app_data["time_bin"], y=app_data["delta_mb"],
                        name=app_name, marker_color=color,
                    ))
                fig_net.update_layout(
                    title="Network Traffic per App (MB per 60s)",
                    yaxis_title="MB", barmode="stack", height=300,
                    xaxis=dict(range=[x_min, x_max]),
                )
                children.append(_full(_timeline_graph(fig_net, 300, zoom_range)))

    if not children or len(children) <= 1:
        children.append(html.P("Insufficient data for timeline.", style={"color": "#888"}))

    return html.Div(children)


def _build_overview_tab(events, dfs, zoom_range=None):
    children = []

    # Event count by collector
    collector_counts = defaultdict(int)
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        collector_counts[sig] += 1
    df_counts = pd.DataFrame(
        sorted(collector_counts.items(), key=lambda x: x[1]),
        columns=["Collector", "Count"],
    )
    fig1 = px.bar(df_counts, x="Count", y="Collector", orientation="h",
                  title="Event Count by Collector", text="Count")
    fig1.update_traces(marker_color="#0f9b8e")

    # Combined: Event Rate + Data Volume (toggleable stacked bar chart)
    all_rows = []
    size_rows = []
    for e in events:
        sig = e.get("signalId", "unknown").split(".")[-1]
        ts = e.get("timestamp")
        if ts:
            dt = pd.to_datetime(ts, unit="ms") + TZ_OFFSET
            all_rows.append({"collector": sig, "datetime": dt})
            size_rows.append({"collector": sig, "datetime": dt,
                              "bytes": len(json.dumps(e, default=str))})

    fig_combined = go.Figure()
    rate_traces = []
    vol_traces = []
    if all_rows:
        df_all = pd.DataFrame(all_rows)
        df_all["time_bin"] = df_all["datetime"].dt.floor("1min")
        density = df_all.groupby(["time_bin", "collector"]).size().reset_index(name="count")
        for coll in density["collector"].unique():
            d = density[density["collector"] == coll]
            fig_combined.add_trace(go.Bar(
                x=d["time_bin"], y=d["count"],
                name=coll, visible=True,
            ))
            rate_traces.append(True)
            vol_traces.append(False)

    if size_rows:
        df_size = pd.DataFrame(size_rows)
        df_size["time_bin"] = df_size["datetime"].dt.floor("1min")
        kb_per_min = df_size.groupby(["time_bin", "collector"])["bytes"].sum().reset_index()
        kb_per_min["KB"] = kb_per_min["bytes"] / 1024
        for coll in kb_per_min["collector"].unique():
            d = kb_per_min[kb_per_min["collector"] == coll]
            fig_combined.add_trace(go.Bar(
                x=d["time_bin"], y=d["KB"],
                name=coll, visible=False,
            ))
            rate_traces.append(False)
            vol_traces.append(True)

    n_total = len(rate_traces)
    fig_combined.update_layout(
        title=dict(text="Event Rate (per minute)", y=0.95),
        barmode="stack",
        updatemenus=[dict(
            type="buttons", direction="right",
            x=0.5, y=1.12, xanchor="center",
            buttons=[
                dict(label="Event Count",
                     method="update",
                     args=[{"visible": [rate_traces[i] or False for i in range(n_total)]},
                           {"title.text": "Event Rate (per minute)", "yaxis.title.text": "Events"}]),
                dict(label="Data Volume",
                     method="update",
                     args=[{"visible": [vol_traces[i] or False for i in range(n_total)]},
                           {"title.text": "Data Volume (KB/min)", "yaxis.title.text": "KB"}]),
            ],
        )],
    )

    children.append(_row(_graph(fig1, 450), _synced_graph(fig_combined, 450, zoom_range)))

    # --- System Impact Section ---
    children.append(html.H3("System Impact",
                             style={"color": PDS_PRIMARY, "marginTop": "32px", "marginBottom": "8px"}))
    children.append(html.P(
        "How does the DataCollector service affect system resources? "
        "Correlating event throughput with memory, FPS, and CPU.",
        style={"color": "#666", "marginBottom": "16px"},
    ))

    # Build per-minute event rate
    event_rows = []
    for e in events:
        ts = e.get("timestamp")
        if ts:
            event_rows.append({"datetime": pd.to_datetime(ts, unit="ms") + TZ_OFFSET})
    if event_rows:
        df_rate = pd.DataFrame(event_rows)
        df_rate["time_bin"] = df_rate["datetime"].dt.floor("1min")
        rate = df_rate.groupby("time_bin").size().reset_index(name="events_per_min")

        # Event Rate vs Available Memory
        mem_exp = _get_memory_df(dfs)
        fig_ev_mem = go.Figure()
        if not mem_exp.empty:
            if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
                mem_exp["availMb"] = pd.to_numeric(mem_exp["availMb"], errors="coerce")
                mem_exp["availGb"] = mem_exp["availMb"] / 1000
                mem_exp["time_bin"] = mem_exp["datetime"].dt.floor("1min")
                mem_avg = mem_exp.groupby("time_bin")["availGb"].mean().reset_index()
                merged = rate.merge(mem_avg, on="time_bin", how="inner")
                if not merged.empty:
                    fig_ev_mem = make_subplots(specs=[[{"secondary_y": True}]])
                    fig_ev_mem.add_trace(go.Bar(x=merged["time_bin"], y=merged["events_per_min"],
                                                name="Events/min", marker_color="rgba(15,155,142,0.5)"),
                                         secondary_y=False)
                    fig_ev_mem.add_trace(go.Scatter(x=merged["time_bin"], y=merged["availGb"],
                                                    mode="lines+markers", name="Available Memory",
                                                    line=dict(color="#e74c3c", width=2)),
                                         secondary_y=True)
                    fig_ev_mem.update_layout(title="Event Throughput vs Available Memory")
                    fig_ev_mem.update_yaxes(title_text="Events/min", secondary_y=False)
                    fig_ev_mem.update_yaxes(title_text="GB", secondary_y=True)

        # Event Rate vs FPS & Dropped Frames
        df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
        fig_ev_fps = go.Figure()
        if not df_fps.empty:
            fps_exp = expand_samples(df_fps)
            if "fps" in fps_exp.columns and "datetime" in fps_exp.columns:
                fps_exp["fps"] = pd.to_numeric(fps_exp["fps"], errors="coerce")
                fps_exp["time_bin"] = fps_exp["datetime"].dt.floor("1min")
                fps_avg = fps_exp.groupby("time_bin")["fps"].mean().reset_index()
                merged_fps = rate.merge(fps_avg, on="time_bin", how="inner")
                if not merged_fps.empty:
                    fig_ev_fps = make_subplots(specs=[[{"secondary_y": True}]])
                    fig_ev_fps.add_trace(go.Bar(x=merged_fps["time_bin"], y=merged_fps["events_per_min"],
                                                name="Events/min", marker_color="rgba(15,155,142,0.5)"),
                                         secondary_y=False)
                    fig_ev_fps.add_trace(go.Scatter(x=merged_fps["time_bin"], y=merged_fps["fps"],
                                                    mode="lines+markers", name="FPS",
                                                    line=dict(color="#2ecc71", width=2)),
                                         secondary_y=True)
                    if "dropped" in fps_exp.columns:
                        fps_exp["dropped"] = pd.to_numeric(fps_exp["dropped"], errors="coerce")
                        drops = fps_exp.groupby("time_bin")["dropped"].sum().reset_index()
                        merged_drops = rate.merge(drops, on="time_bin", how="inner")
                        fig_ev_fps.add_trace(go.Scatter(x=merged_drops["time_bin"], y=merged_drops["dropped"],
                                                        mode="lines+markers", name="Dropped Frames",
                                                        line=dict(color="#e74c3c", dash="dot")),
                                             secondary_y=True)
                    fig_ev_fps.update_layout(title="Event Throughput vs FPS & Dropped Frames")
                    fig_ev_fps.update_yaxes(title_text="Events/min", secondary_y=False)
                    fig_ev_fps.update_yaxes(title_text="FPS / Drops", secondary_y=True)

        children.append(_row(_synced_graph(fig_ev_mem, 400, zoom_range), _synced_graph(fig_ev_fps, 400, zoom_range)))

        # Event Rate vs CPU (if available)
        cpu_exp = _get_cpu_df(dfs)
        if not cpu_exp.empty:
            if "usagePct" in cpu_exp.columns and "datetime" in cpu_exp.columns:
                cpu_exp["usagePct"] = pd.to_numeric(cpu_exp["usagePct"], errors="coerce")
                cpu_exp["time_bin"] = cpu_exp["datetime"].dt.floor("1min")
                cpu_avg = cpu_exp.groupby("time_bin")["usagePct"].mean().reset_index()
                merged_cpu = rate.merge(cpu_avg, on="time_bin", how="inner")
                if not merged_cpu.empty:
                    fig_ev_cpu = make_subplots(specs=[[{"secondary_y": True}]])
                    fig_ev_cpu.add_trace(go.Bar(x=merged_cpu["time_bin"], y=merged_cpu["events_per_min"],
                                                name="Events/min", marker_color="rgba(15,155,142,0.5)"),
                                         secondary_y=False)
                    fig_ev_cpu.add_trace(go.Scatter(x=merged_cpu["time_bin"], y=merged_cpu["usagePct"],
                                                    mode="lines+markers", name="CPU Usage (%)",
                                                    line=dict(color="#9b59b6", width=2)),
                                         secondary_y=True)
                    fig_ev_cpu.update_layout(title="Event Throughput vs CPU Usage")
                    fig_ev_cpu.update_yaxes(title_text="Events/min", secondary_y=False)
                    fig_ev_cpu.update_yaxes(title_text="CPU %", secondary_y=True)
                    children.append(_full(_synced_graph(fig_ev_cpu, 380, zoom_range)))

        # Self-Monitor (if available)
        df_self = dfs.get("SelfMonitor_Usage", pd.DataFrame())
        if not df_self.empty:
            self_exp = expand_samples(df_self)
            if "datetime" in self_exp.columns:
                sm_figs = []
                if "cpuPct" in self_exp.columns:
                    self_exp["cpuPct"] = pd.to_numeric(self_exp["cpuPct"], errors="coerce")
                    fig_sm = px.line(self_exp.dropna(subset=["cpuPct"]),
                                     x="datetime", y="cpuPct",
                                     title="DataCollector Own CPU (%)", markers=True)
                    fig_sm.update_traces(line=dict(color="#e74c3c"))
                    sm_figs.append(_synced_graph(fig_sm, 300, zoom_range))
                if "memMb" in self_exp.columns:
                    self_exp["memMb"] = pd.to_numeric(self_exp["memMb"], errors="coerce")
                    fig_sm_m = px.line(self_exp.dropna(subset=["memMb"]),
                                       x="datetime", y="memMb",
                                       title="DataCollector Own Memory (MB)", markers=True)
                    fig_sm_m.update_traces(line=dict(color="#5b8def"))
                    sm_figs.append(_synced_graph(fig_sm_m, 300, zoom_range))
                if len(sm_figs) >= 2:
                    children.append(_row(sm_figs[0], sm_figs[1]))
                elif sm_figs:
                    children.append(_full(sm_figs[0]))

    if len(children) <= 3:
        children.append(html.P(
            "Tip: Deploy CpuCollector and SelfMonitorCollector for deeper impact analysis.",
            style={"color": "#999", "fontStyle": "italic"},
        ))

    return html.Div(children)


def _build_app_usage_tab(dfs, displays, apps, zoom_range=None):
    df = dfs.get("App_FocusChanged")
    if df is None or df.empty:
        return html.P("No App_FocusChanged data available.", style={"color": "#888"})

    children = []
    df["current_pkg"] = df.apply(lambda r: str(r.get("current.package", "") or ""), axis=1)
    df["current_class"] = df.apply(lambda r: str(r.get("current.class", "") or ""), axis=1)
    df["display_id"] = df.apply(
        lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0, axis=1
    )
    df_physical = df[df["display_id"].isin(displays)].copy()

    if apps:
        df_physical = df_physical[df_physical["current_pkg"].isin(apps)]

    if df_physical.empty:
        return html.P("No data for selected filters.", style={"color": "#888"})

    # Focus frequency
    fig1 = go.Figure()
    for did in sorted(df_physical["display_id"].unique()):
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        color = DISPLAY_COLORS.get(did, "#888")
        df_d = df_physical[df_physical["display_id"] == did]
        counts = df_d["current_pkg"].value_counts().reset_index()
        counts.columns = ["Package", "Count"]
        counts["Short"] = counts["Package"].apply(_short_name)
        fig1.add_trace(go.Bar(x=counts["Short"], y=counts["Count"], name=name, marker_color=color))
    fig1.update_layout(title="App Focus Frequency", barmode="group",
                       xaxis={"categoryorder": "total descending"})

    # Time in package
    df_sorted = df_physical.sort_values("datetime").reset_index(drop=True)
    fig2 = go.Figure()
    for did in sorted(df_physical["display_id"].unique()):
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 2:
            continue
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        color = DISPLAY_COLORS.get(did, "#888")
        durations = []
        for i in range(len(df_disp) - 1):
            pkg = str(df_disp.iloc[i]["current_pkg"])
            if not pkg or pkg == "nan":
                continue
            dur = (df_disp.iloc[i + 1]["timestamp"] - df_disp.iloc[i]["timestamp"]) / 1000
            if 0 < dur < 3600:
                durations.append({"Short": _short_name(pkg), "duration_s": dur})
        if durations:
            df_dur = pd.DataFrame(durations).groupby("Short")["duration_s"].sum().reset_index()
            fig2.add_trace(go.Bar(x=df_dur["Short"], y=df_dur["duration_s"], name=name, marker_color=color))
    fig2.update_layout(title="Time in App (s)", barmode="group",
                       xaxis={"categoryorder": "total descending"}, yaxis_title="Seconds")

    # Timeline Gantt — one row per display, same color per app
    active_displays = sorted(d for d in df_physical["display_id"].unique() if d < 10)
    n_disp = max(len(active_displays), 1)
    display_labels = [DISPLAY_NAMES.get(d, f"Display {d}") for d in active_displays]

    fig3 = go.Figure()
    legend_added = set()

    for idx, did in enumerate(active_displays):
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if df_disp.empty:
            continue
        y_val = idx
        for i in range(len(df_disp)):
            pkg = str(df_disp.iloc[i]["current_pkg"])
            short = _short_name(pkg)
            start = df_disp.iloc[i]["datetime"]
            if i + 1 < len(df_disp):
                next_dt = df_disp.iloc[i + 1]["datetime"]
                end = _clamp_to_active(start, next_dt)
            else:
                end = _clamp_to_active(start, _session_end_for(start))
            dur_s = (end - start).total_seconds()
            if 0 < dur_s < 86400:
                color = APP_COLOR_MAP.get(short, "#888")
                fig3.add_shape(
                    type="rect", x0=start, x1=end,
                    y0=y_val - 0.4, y1=y_val + 0.4,
                    fillcolor=color, opacity=0.85,
                    line=dict(width=0.5, color="#fff"),
                )
                # Add one legend entry per app
                if short not in legend_added:
                    legend_added.add(short)
                    fig3.add_trace(go.Scatter(
                        x=[None], y=[None], mode="markers",
                        marker=dict(size=12, color=color, symbol="square"),
                        name=short, legendgroup=short, showlegend=True,
                    ))
                # Label if wide enough
                if dur_s > 15:
                    mid = start + (end - start) / 2
                    fig3.add_annotation(
                        x=mid, y=y_val, text=short,
                        showarrow=False, font=dict(size=8, color="#222"),
                    )
    fig3.update_layout(
        title="App Timeline — All Screens",
        height=120 * n_disp + 100,
        yaxis=dict(
            tickvals=list(range(len(display_labels))),
            ticktext=display_labels,
            range=[-0.5, len(display_labels) - 0.5],
        ),
        xaxis=dict(type="date"),
        margin=dict(l=120, r=30, t=80, b=60),
    )

    children.append(_row(_graph(fig1, 400), _graph(fig2, 400)))
    children.append(_full(_synced_graph(fig3, 120 * n_disp + 100, zoom_range)))

    # Sankey: app transitions per display
    sankey_figs = []
    for did in sorted(df_physical["display_id"].unique()):
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 3:
            continue
        transitions = defaultdict(int)
        for i in range(len(df_disp) - 1):
            src = _short_name(str(df_disp.iloc[i]["current_pkg"]))
            tgt = _short_name(str(df_disp.iloc[i + 1]["current_pkg"]))
            if src and tgt and src != tgt and src != "unknown" and tgt != "unknown":
                transitions[(src, tgt)] += 1
        if not transitions:
            continue
        # Build Sankey nodes/links
        labels_set = set()
        for (s, t) in transitions:
            labels_set.add(s)
            labels_set.add(t)
        labels = sorted(labels_set)
        label_idx = {l: i for i, l in enumerate(labels)}
        sources, targets, values = [], [], []
        for (s, t), v in sorted(transitions.items(), key=lambda x: -x[1]):
            sources.append(label_idx[s])
            targets.append(label_idx[t])
            values.append(v)
        fig_sankey = go.Figure(data=[go.Sankey(
            node=dict(label=labels, pad=15, thickness=20,
                      color="#0f9b8e"),
            link=dict(source=sources, target=targets, value=values,
                      color="rgba(15,155,142,0.25)"),
        )])
        fig_sankey.update_layout(title=f"App Transitions — {name}")
        sankey_figs.append(_graph(fig_sankey, 450))

    for sf in sankey_figs:
        children.append(_full(sf))

    # Class-level transition Sankey per display
    class_sankey_figs = []
    for did in sorted(df_physical["display_id"].unique()):
        name = DISPLAY_NAMES.get(did, f"Display {did}")
        df_disp = df_sorted[df_sorted["display_id"] == did].reset_index(drop=True)
        if len(df_disp) < 3:
            continue
        transitions = defaultdict(int)
        for i in range(len(df_disp) - 1):
            src_pkg = _short_name(str(df_disp.iloc[i]["current_pkg"]))
            src_cls = str(df_disp.iloc[i].get("current_class", "")).rsplit(".", 1)[-1] or "?"
            tgt_pkg = _short_name(str(df_disp.iloc[i + 1]["current_pkg"]))
            tgt_cls = str(df_disp.iloc[i + 1].get("current_class", "")).rsplit(".", 1)[-1] or "?"
            src = f"{src_pkg}/{src_cls}"
            tgt = f"{tgt_pkg}/{tgt_cls}"
            if src != tgt and "unknown" not in src and "unknown" not in tgt:
                transitions[(src, tgt)] += 1
        if not transitions:
            continue
        labels_set = set()
        for (s, t) in transitions:
            labels_set.add(s)
            labels_set.add(t)
        labels = sorted(labels_set)
        label_idx = {l: i for i, l in enumerate(labels)}
        sources, targets, values = [], [], []
        for (s, t), v in sorted(transitions.items(), key=lambda x: -x[1])[:30]:
            sources.append(label_idx[s])
            targets.append(label_idx[t])
            values.append(v)
        fig_cs = go.Figure(data=[go.Sankey(
            node=dict(label=labels, pad=15, thickness=20, color="#5b8def"),
            link=dict(source=sources, target=targets, value=values,
                      color="rgba(91,141,239,0.2)"),
        )])
        fig_cs.update_layout(title=f"App/Class Transitions — {name}")
        class_sankey_figs.append(_graph(fig_cs, 500))

    for sf in class_sankey_figs:
        children.append(_full(sf))

    return html.Div(children)


def _build_touch_tab(dfs, displays, zoom_range=None):
    # Only Touch_Down: each interaction = exactly one Touch_Down;
    # Touch_Swipe has no x/y coordinates, Touch_Up duplicates Touch_Down.
    touch_dfs = [dfs.get("Touch_Down", pd.DataFrame())]
    touch_dfs = [df for df in touch_dfs if not df.empty]
    if not touch_dfs:
        return html.P("No touch data available.", style={"color": "#888"})

    children = []
    all_touch = pd.concat(touch_dfs, ignore_index=True)

    # --- 1) Touch Rate vs Dropped Frames ---
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    fig_touch_fps = go.Figure()
    if not all_touch.empty and not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_touch_fps = make_subplots(specs=[[{"secondary_y": True}]])
            fps_exp["time_bin"] = fps_exp["datetime"].dt.floor("5s")
            drops = fps_exp.groupby("time_bin")["dropped"].sum().reset_index()
            fig_touch_fps.add_trace(go.Scatter(
                x=drops["time_bin"], y=drops["dropped"],
                mode="lines", name="Dropped Frames",
                line=dict(color="#e74c3c", width=2)),
                secondary_y=True)
            if "displayId" in all_touch.columns:
                for did in sorted(all_touch["displayId"].dropna().unique()):
                    did_int = int(did)
                    if did_int not in displays:
                        continue
                    df_d = all_touch[all_touch["displayId"] == did_int].copy()
                    df_d["time_bin"] = df_d["datetime"].dt.floor("5s")
                    rate_d = df_d.groupby("time_bin").size().reset_index(name="touches")
                    rate_d["tpm"] = rate_d["touches"] * 12
                    fig_touch_fps.add_trace(go.Bar(
                        x=rate_d["time_bin"], y=rate_d["tpm"],
                        name=f"Touches — {DISPLAY_NAMES.get(did_int, '')}",
                        marker_color=DISPLAY_COLORS.get(did_int, "#888"),
                        opacity=0.7), secondary_y=False)
            fig_touch_fps.update_layout(title="Touch Rate vs Dropped Frames", barmode="stack")
            fig_touch_fps.update_yaxes(title_text="Touches/min", secondary_y=False)
            fig_touch_fps.update_yaxes(title_text="Dropped Frames", secondary_y=True)
    children.append(_full(_synced_graph(fig_touch_fps, 400, zoom_range)))

    # --- 2) Touch Heatmaps — one per row, fixed aspect ratio ---
    df_heatmap = dfs.get("Touch_Down", pd.DataFrame()).copy()

    # Filter heatmap data by zoom range if set
    if not df_heatmap.empty and zoom_range and "datetime" in df_heatmap.columns:
        z_start = pd.to_datetime(zoom_range[0])
        z_end = pd.to_datetime(zoom_range[1])
        df_heatmap = df_heatmap[(df_heatmap["datetime"] >= z_start) & (df_heatmap["datetime"] <= z_end)]

    if not df_heatmap.empty and "displayId" in df_heatmap.columns:
        # Pre-compute Gaussian KDE grids and shared color scale
        global_zmax = 0
        heatmap_data = []
        for did in sorted(displays):
            if did in TOUCH_EXCLUDED_DISPLAYS:
                continue
            df_disp = df_heatmap[df_heatmap["displayId"] == did]
            if df_disp.empty or "x" not in df_disp.columns:
                continue
            res = DISPLAY_RESOLUTIONS.get(did, (1920, 720))
            n = len(df_disp)
            bin_size = 80 if n < 50 else (40 if n < 200 else 20)
            cell = max(10, bin_size // 2)
            gx = np.linspace(0, res[0], res[0] // cell + 1)
            gy = np.linspace(0, res[1], res[1] // cell + 1)
            GX, GY = np.meshgrid(gx, gy)
            Z = np.zeros_like(GX, dtype=float)
            sigma = bin_size * 1.2
            two_sigma2 = 2.0 * sigma * sigma
            for _, row in df_disp.iterrows():
                tx, ty = row["x"], row["y"]
                if pd.isna(tx) or pd.isna(ty):
                    continue
                Z += np.exp(-((GX - tx) ** 2 + (GY - ty) ** 2) / two_sigma2)
            local_max = float(Z.max()) if Z.size > 0 else 0
            global_zmax = max(global_zmax, local_max)
            heatmap_data.append((did, res, n, gx, gy, Z))

        for did, res, n, gx, gy, Z in heatmap_data:
            name = DISPLAY_NAMES.get(did, f"Display {did}")
            fig = go.Figure()
            fig.add_trace(go.Heatmap(
                x=gx, y=gy, z=Z,
                colorscale=[[0, "rgba(30,30,50,0)"], [0.15, "#4575b4"], [0.35, "#91bfdb"],
                            [0.5, "#fee090"], [0.7, "#fc8d59"], [1.0, "#d73027"]],
                zsmooth="best",
                zmin=0, zmax=global_zmax if global_zmax > 0 else None,
                showscale=True,
            ))
            fig.add_shape(type="rect", x0=0, y0=0, x1=res[0], y1=res[1],
                          line=dict(color="#555", width=2))
            fig.update_layout(
                title=f"Touch Heatmap — {name} (n={n})",
                xaxis=dict(range=[0, res[0]], showgrid=False, constrain="domain"),
                yaxis=dict(range=[res[1], 0], showgrid=False, scaleanchor="x", constrain="domain"),
                margin=dict(l=60, r=60, t=80, b=30),
            )
            fig_h = max(400, int(res[1] / res[0] * 1400) + 80)
            children.append(_full(_graph(fig, fig_h)))

    return html.Div(children)


def _build_performance_tab(dfs, zoom_range=None):
    children = []

    # Memory
    mem_exp = _get_memory_df(dfs)
    fig_mem = go.Figure()
    if not mem_exp.empty:
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            mem_exp["availGb"] = pd.to_numeric(mem_exp["availMb"], errors="coerce") / 1000
            fig_mem.add_trace(go.Scatter(x=mem_exp["datetime"], y=mem_exp["availGb"],
                                         mode="lines+markers", name="Available",
                                         line=dict(color="#0f9b8e")))
            total_gb = None
            if "totalMem" in mem_exp.columns:
                total_vals = mem_exp["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_gb = float(total_vals.iloc[0]) / (1024 * 1024 * 1000)
            elif "totalMem_mb" in mem_exp.columns:
                total_vals = pd.to_numeric(mem_exp["totalMem_mb"], errors="coerce").dropna()
                if len(total_vals) > 0:
                    total_gb = float(total_vals.iloc[0]) / 1000
            if total_gb:
                fig_mem.add_hline(y=total_gb, line_dash="dash", line_color="red",
                                  annotation_text=f"Total: {total_gb:.1f} GB")
            fig_mem.update_layout(title="Memory Over Time", yaxis_title="GB")

    children.append(_full(_synced_graph(fig_mem, 380, zoom_range)))

    # Dropped Frames (own row)
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    if not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_dropped = go.Figure()
            fig_dropped.add_trace(go.Bar(
                x=fps_exp["datetime"], y=fps_exp["dropped"],
                name="Dropped Frames", marker_color="rgba(231,76,60,0.85)",
            ))
            fig_dropped.update_layout(title="Dropped Frames", yaxis_title="Frames")
            children.append(_full(_synced_graph(fig_dropped, 350, zoom_range)))

    # CPU Usage
    cpu_exp = _get_cpu_df(dfs)
    if not cpu_exp.empty:
        if "usagePct" in cpu_exp.columns and "datetime" in cpu_exp.columns:
            cpu_exp["usagePct"] = pd.to_numeric(cpu_exp["usagePct"], errors="coerce")
            fig_cpu = px.line(cpu_exp.dropna(subset=["usagePct"]),
                              x="datetime", y="usagePct",
                              title="System CPU Usage (%)", markers=True)
            fig_cpu.update_traces(line=dict(color="#e74c3c"))
            fig_cpu.add_hline(y=100, line_dash="dash", line_color="gray")

            fig_load = go.Figure()
            if "loadAvg1min" in cpu_exp.columns:
                cpu_exp["loadAvg1min"] = pd.to_numeric(cpu_exp["loadAvg1min"], errors="coerce")
                fig_load = px.line(cpu_exp.dropna(subset=["loadAvg1min"]),
                                   x="datetime", y="loadAvg1min",
                                   title="1-min Load Average", markers=True)
                fig_load.update_traces(line=dict(color="#9b59b6"))
            children.append(_row(_synced_graph(fig_cpu, 350, zoom_range), _synced_graph(fig_load, 350, zoom_range)))

    # Storage Usage
    df_stor = dfs.get("Storage_Usage", pd.DataFrame())
    if not df_stor.empty and "datetime" in df_stor.columns:
        fig_stor = make_subplots(specs=[[{"secondary_y": True}]])
        if "usedBytes" in df_stor.columns and "availableBytes" in df_stor.columns:
            df_stor["usedGb"] = pd.to_numeric(df_stor["usedBytes"], errors="coerce") / (1024 ** 3)
            df_stor["availGb"] = pd.to_numeric(df_stor["availableBytes"], errors="coerce") / (1024 ** 3)
            fig_stor.add_trace(go.Scatter(x=df_stor["datetime"], y=df_stor["usedGb"],
                                          mode="lines+markers", name="Used (GB)",
                                          line=dict(color="#e74c3c")), secondary_y=False)
            fig_stor.add_trace(go.Scatter(x=df_stor["datetime"], y=df_stor["availGb"],
                                          mode="lines+markers", name="Available (GB)",
                                          line=dict(color="#0f9b8e")), secondary_y=False)
        if "usagePercent" in df_stor.columns:
            fig_stor.add_trace(go.Scatter(x=df_stor["datetime"],
                                          y=pd.to_numeric(df_stor["usagePercent"], errors="coerce"),
                                          mode="lines+markers", name="Usage (%)",
                                          line=dict(color="#f39c12", dash="dash")), secondary_y=True)
            fig_stor.update_yaxes(title_text="Usage %", range=[0, 100], secondary_y=True)
        fig_stor.update_layout(title="Storage Usage Over Time")
        fig_stor.update_yaxes(title_text="GB", secondary_y=False)
        children.append(_full(_synced_graph(fig_stor, 350, zoom_range)))

    # Battery
    df_batt = dfs.get("Battery_Level", pd.DataFrame())
    if not df_batt.empty and "datetime" in df_batt.columns:
        batt_exp = expand_samples(df_batt)
        if "levelPct" in batt_exp.columns and "datetime" in batt_exp.columns:
            batt_exp["levelPct"] = pd.to_numeric(batt_exp["levelPct"], errors="coerce")
            fig_batt = px.line(batt_exp.dropna(subset=["levelPct"]),
                               x="datetime", y="levelPct",
                               title="Battery Level (%)", markers=True)
            fig_batt.update_traces(line=dict(color="#2ecc71"))
            fig_batt.update_layout(yaxis=dict(range=[0, 105]))
            children.append(_full(_synced_graph(fig_batt, 280, zoom_range)))

    return html.Div(children)


def _build_network_tab(dfs, apps, apn_filter, transport_filter, zoom_range=None):
    children = []

    # Signal strength
    df_signal = dfs.get("Connectivity_SignalStrength", pd.DataFrame())
    fig_signal = go.Figure()
    fig_quality = go.Figure()
    if not df_signal.empty:
        sig_exp = expand_samples(df_signal)
        if "signalStrengthDbm" in sig_exp.columns and "datetime" in sig_exp.columns:
            fig_signal = px.line(sig_exp, x="datetime", y="signalStrengthDbm",
                                title="Signal Strength (dBm)", markers=True)

            # Quality + bandwidth
            sig_exp["quality_pct"] = (2 * (sig_exp["signalStrengthDbm"] + 100)).clip(0, 100)
            fig_quality = make_subplots(specs=[[{"secondary_y": True}]])
            if "maxDownstreamBandwidthKbps" in sig_exp.columns:
                sig_exp["down_mbps"] = sig_exp["maxDownstreamBandwidthKbps"] / 1000
                sig_exp["up_mbps"] = sig_exp["maxUpstreamBandwidthKbps"] / 1000
                fig_quality.add_trace(go.Scatter(x=sig_exp["datetime"], y=sig_exp["down_mbps"],
                                                 mode="lines", name="Download (Mbps)",
                                                 line=dict(color="#5b8def", width=1.5)), secondary_y=True)
                fig_quality.add_trace(go.Scatter(x=sig_exp["datetime"], y=sig_exp["up_mbps"],
                                                 mode="lines", name="Upload (Mbps)",
                                                 line=dict(color="#f39c12", width=1.5)), secondary_y=True)
            fig_quality.add_trace(go.Scatter(x=sig_exp["datetime"], y=sig_exp["quality_pct"],
                                             mode="lines+markers", name="Quality (%)",
                                             line=dict(color="#2ecc71", width=3, dash="dash"),
                                             marker=dict(size=4)), secondary_y=False)
            fig_quality.update_layout(title="Signal Quality & Bandwidth")
            fig_quality.update_yaxes(title_text="Quality (%)", range=[0, 105], secondary_y=False)
            fig_quality.update_yaxes(title_text="Mbps", secondary_y=True)

    children.append(_row(_synced_graph(fig_signal, 350, zoom_range), _synced_graph(fig_quality, 350, zoom_range)))

    # All Network Totals (WiFi, Mobile, Hotspot, Ethernet)
    fig_traffic = go.Figure()
    net_sources = [
        ("Network_WifiTotal", "WiFi", "#0f9b8e"),
        ("Network_MobileTotal", "Mobile", "#e74c3c"),
        ("Network_VehicleHotspotTotal", "Vehicle Hotspot", "#9b59b6"),
        ("Network_EthernetTotal", "Ethernet", "#f39c12"),
    ]
    for action_name, label, color in net_sources:
        df_net = dfs.get(action_name, pd.DataFrame())
        if not df_net.empty and "datetime" in df_net.columns:
            if "rxBytes" in df_net.columns:
                fig_traffic.add_trace(go.Scatter(
                    x=df_net["datetime"], y=pd.to_numeric(df_net["rxBytes"], errors="coerce") / (1024 * 1024),
                    mode="lines", name=f"{label} RX", line=dict(color=color, width=2)))
            if "txBytes" in df_net.columns:
                fig_traffic.add_trace(go.Scatter(
                    x=df_net["datetime"], y=pd.to_numeric(df_net["txBytes"], errors="coerce") / (1024 * 1024),
                    mode="lines", name=f"{label} TX", line=dict(color=color, width=1, dash="dot")))
    fig_traffic.update_layout(title="Network Traffic by Interface (Cumulative MB)", yaxis_title="MB")

    # Vehicle Hotspot Clients
    df_hotspot = dfs.get("Network_VehicleHotspotTotal", pd.DataFrame())
    fig_hotspot = go.Figure()
    if not df_hotspot.empty and "connectedClients" in df_hotspot.columns and "datetime" in df_hotspot.columns:
        fig_hotspot.add_trace(go.Scatter(
            x=df_hotspot["datetime"],
            y=pd.to_numeric(df_hotspot["connectedClients"], errors="coerce"),
            mode="lines+markers", name="Connected Clients",
            line=dict(color="#9b59b6", width=2),
        ))
        fig_hotspot.update_layout(title="Vehicle Hotspot — Connected Clients", yaxis_title="Clients")

    children.append(_row(_synced_graph(fig_traffic, 350, zoom_range), _synced_graph(fig_hotspot, 350, zoom_range)))

    # APN Traffic — extracted from Network_PerAppTraffic apps entries
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    fig_apn = go.Figure()
    fig_transport = go.Figure()
    if not df_perapp.empty and "apps" in df_perapp.columns:
        # Parse all app entries to get APN and networkType breakdowns over time
        apn_rows = []
        for _, row in df_perapp.sort_values("timestamp").iterrows():
            dt = row.get("datetime")
            apps_data = row.get("apps", [])
            schema = row.get("schema")
            if not apps_data:
                continue
            for a in apps_data:
                if isinstance(a, dict):
                    rec = a
                elif isinstance(a, list) and schema:
                    rec = dict(zip(schema, a))
                else:
                    continue
                apn_rows.append({
                    "datetime": dt,
                    "apn": rec.get("apn", "unknown"),
                    "networkType": rec.get("networkType", "unknown"),
                    "rx": rec.get("rxBytes", 0),
                    "tx": rec.get("txBytes", 0),
                })
        if apn_rows:
            df_apn_data = pd.DataFrame(apn_rows)
            # APN Traffic over time (cumulative per APN)
            apn_colors = ["#0f9b8e", "#5b8def", "#e74c3c", "#f39c12", "#9b59b6", "#2ecc71"]
            apn_totals = df_apn_data.groupby(["datetime", "apn"])[["rx", "tx"]].sum().reset_index()
            for i, apn_name in enumerate(sorted(apn_totals["apn"].unique())):
                apn_df = apn_totals[apn_totals["apn"] == apn_name].sort_values("datetime")
                color = apn_colors[i % len(apn_colors)]
                fig_apn.add_trace(go.Scatter(
                    x=apn_df["datetime"], y=apn_df["rx"].cumsum() / (1024 * 1024),
                    mode="lines", name=f"{apn_name} RX",
                    line=dict(color=color, width=2)))
                fig_apn.add_trace(go.Scatter(
                    x=apn_df["datetime"], y=apn_df["tx"].cumsum() / (1024 * 1024),
                    mode="lines", name=f"{apn_name} TX",
                    line=dict(color=color, width=1, dash="dot")))
            fig_apn.update_layout(title="Traffic by APN (Cumulative MB)", yaxis_title="MB")

            # Transport type traffic over time
            transport_totals = df_apn_data.groupby(["datetime", "networkType"])[["rx", "tx"]].sum().reset_index()
            for nt in sorted(transport_totals["networkType"].unique()):
                nt_df = transport_totals[transport_totals["networkType"] == nt].sort_values("datetime")
                color = TRANSPORT_COLORS.get(nt, "#888")
                fig_transport.add_trace(go.Scatter(
                    x=nt_df["datetime"], y=nt_df["rx"].cumsum() / (1024 * 1024),
                    mode="lines", name=f"{nt} RX",
                    line=dict(color=color, width=2)))
                fig_transport.add_trace(go.Scatter(
                    x=nt_df["datetime"], y=nt_df["tx"].cumsum() / (1024 * 1024),
                    mode="lines", name=f"{nt} TX",
                    line=dict(color=color, width=1, dash="dot")))
            fig_transport.update_layout(title="Traffic by Transport Type (Cumulative MB)", yaxis_title="MB")

    children.append(_row(_synced_graph(fig_apn, 350, zoom_range), _synced_graph(fig_transport, 350, zoom_range)))

    # Per-app traffic with APN + Transport breakdown (stacked by transport)
    fig_perapp = go.Figure()
    perapp_height = 350
    if not df_perapp.empty and "apps" in df_perapp.columns:
        # Filter by zoom range if set
        df_pa = df_perapp
        if zoom_range and "datetime" in df_pa.columns:
            z_start = pd.to_datetime(zoom_range[0])
            z_end = pd.to_datetime(zoom_range[1])
            df_pa = df_pa[(df_pa["datetime"] >= z_start) & (df_pa["datetime"] <= z_end)]

        app_traffic_rows = []
        for _, row in df_pa.sort_values("timestamp").iterrows():
            apps_data = row.get("apps", [])
            schema = row.get("schema")
            if not apps_data:
                continue
            for a in apps_data:
                if isinstance(a, dict):
                    rec = a
                elif isinstance(a, list) and schema:
                    rec = dict(zip(schema, a))
                else:
                    continue
                pkgs = rec.get("packages", [])
                label = pkgs[0] if pkgs else f"uid:{rec.get('uid', 0)}"
                if apps and label not in apps:
                    continue
                apn = rec.get("apn", "unknown")
                net_type = rec.get("networkType", "unknown")
                # Apply local APN/transport filter for this graph
                if apn_filter and apn not in apn_filter:
                    continue
                if transport_filter and net_type not in transport_filter:
                    continue
                app_traffic_rows.append({
                    "app": _short_name(label),
                    "transport": f"{apn}/{net_type}",
                    "rx": rec.get("rxBytes", 0),
                    "tx": rec.get("txBytes", 0),
                })

        if app_traffic_rows:
            df_at = pd.DataFrame(app_traffic_rows)
            # Sum per app + transport combo
            app_transport_sums = df_at.groupby(["app", "transport"])[["rx", "tx"]].sum().reset_index()
            app_transport_sums["rx_mb"] = app_transport_sums["rx"] / (1024 * 1024)
            app_transport_sums["tx_mb"] = app_transport_sums["tx"] / (1024 * 1024)
            # Filter out tiny entries
            app_transport_sums = app_transport_sums[
                (app_transport_sums["rx_mb"] + app_transport_sums["tx_mb"]) > 0.001
            ]

            if not app_transport_sums.empty:
                # Sort apps by total traffic
                app_totals = app_transport_sums.groupby("app")[["rx_mb", "tx_mb"]].sum()
                app_totals["total"] = app_totals["rx_mb"] + app_totals["tx_mb"]
                app_order = app_totals.sort_values("total", ascending=True).index.tolist()

                # Color palette for transport types
                transport_palette = ["#0f9b8e", "#5b8def", "#e74c3c", "#f39c12", "#9b59b6", "#2ecc71", "#888"]
                all_transports = sorted(app_transport_sums["transport"].unique())
                transport_colors = {t: transport_palette[i % len(transport_palette)]
                                    for i, t in enumerate(all_transports)}

                # Add stacked bars: RX traces per transport
                for transport in all_transports:
                    t_data = app_transport_sums[app_transport_sums["transport"] == transport]
                    # Create a series aligned to app_order
                    rx_vals = []
                    tx_vals = []
                    for app in app_order:
                        match = t_data[t_data["app"] == app]
                        rx_vals.append(match["rx_mb"].iloc[0] if not match.empty else 0)
                        tx_vals.append(match["tx_mb"].iloc[0] if not match.empty else 0)

                    color = transport_colors[transport]
                    fig_perapp.add_trace(go.Bar(
                        y=app_order, x=rx_vals, name=f"{transport}",
                        orientation="h", marker_color=color,
                        legendgroup=transport, offsetgroup="rx",
                    ))
                    fig_perapp.add_trace(go.Bar(
                        y=app_order, x=tx_vals, name=f"{transport}",
                        orientation="h", marker_color=color, opacity=0.6,
                        legendgroup=transport, offsetgroup="tx",
                        showlegend=False,
                    ))

                n_apps = len(app_order)
                fig_perapp.update_layout(
                    title="Per-App Traffic by APN/Transport (MB) — solid=RX, faded=TX",
                    barmode="stack", height=max(300, n_apps * 45 + 100),
                    xaxis_title="MB",
                    yaxis=dict(categoryorder="array", categoryarray=app_order),
                )
                perapp_height = max(350, n_apps * 45 + 120)

    children.append(_full(_graph(fig_perapp, perapp_height)))

    # App Lifecycle vs Network Traffic
    df_focus_lc = dfs.get("App_FocusChanged", pd.DataFrame())
    fig_lifecycle = go.Figure()
    if not df_perapp.empty and "apps" in df_perapp.columns and not df_focus_lc.empty:
        traffic_rows = []
        for _, row in df_perapp.sort_values("timestamp").iterrows():
            dt = row.get("datetime")
            ts = row.get("timestamp")
            apps_data = row.get("apps", [])
            schema = row.get("schema")
            if not apps_data or not isinstance(apps_data, list):
                continue
            for a in apps_data:
                if isinstance(a, dict):
                    pkgs = a.get("packages", [])
                    label = pkgs[0] if pkgs else f"uid:{a.get('uid', 0)}"
                    if apps and label not in apps:
                        continue
                    traffic_rows.append({"datetime": dt, "timestamp": ts, "app": label,
                                         "rx": a.get("rxBytes", 0), "tx": a.get("txBytes", 0)})
                elif isinstance(a, list) and schema:
                    rec = dict(zip(schema, a))
                    pkgs = rec.get("packages", [])
                    label = pkgs[0] if pkgs else f"uid:{rec.get('uid', 0)}"
                    if apps and label not in apps:
                        continue
                    traffic_rows.append({"datetime": dt, "timestamp": ts, "app": label,
                                         "rx": rec.get("rxBytes", 0), "tx": rec.get("txBytes", 0)})
        if traffic_rows:
            df_tr = pd.DataFrame(traffic_rows).sort_values(["app", "timestamp"])
            df_tr["total"] = df_tr["rx"] + df_tr["tx"]
            df_tr["delta"] = df_tr.groupby("app")["total"].diff().fillna(0).clip(lower=0)
            df_tr["delta_mb"] = df_tr["delta"] / (1024 * 1024)
            app_totals_lc = df_tr.groupby("app")["delta"].sum().sort_values(ascending=False)
            top_apps_lc = [a for a in app_totals_lc.index if app_totals_lc[a] > 1024][:6]
            if top_apps_lc:
                fg_periods = defaultdict(list)
                df_fc = df_focus_lc.copy().sort_values("timestamp")
                df_fc["pkg"] = df_fc.apply(lambda r: str(r.get("current.package", "") or ""), axis=1)
                df_fc["did"] = df_fc.apply(
                    lambda r: int(r.get("current.displayId") or 0) if pd.notna(r.get("current.displayId")) else 0,
                    axis=1)
                for did in df_fc["did"].unique():
                    if did >= 10:
                        continue
                    df_d = df_fc[df_fc["did"] == did].reset_index(drop=True)
                    for i in range(len(df_d)):
                        pkg = df_d.iloc[i]["pkg"]
                        start = df_d.iloc[i]["datetime"]
                        end = df_d.iloc[i + 1]["datetime"] if i + 1 < len(df_d) else df_tr["datetime"].max()
                        fg_periods[pkg].append((start, end, did))
                n_lc = len(top_apps_lc)
                fig_lifecycle = make_subplots(rows=n_lc, cols=1, shared_xaxes=True,
                                             subplot_titles=[_short_name(a) for a in top_apps_lc],
                                             vertical_spacing=0.04)
                for idx, app_name in enumerate(top_apps_lc, 1):
                    app_df = df_tr[df_tr["app"] == app_name]
                    fig_lifecycle.add_trace(go.Scatter(
                        x=app_df["datetime"], y=app_df["delta_mb"], mode="lines",
                        name=_short_name(app_name), line=dict(color="#e74c3c", width=2),
                        showlegend=(idx == 1), legendgroup="traffic"), row=idx, col=1)
                    for start, end, did in fg_periods.get(app_name, []):
                        color = DISPLAY_COLORS.get(did, "#888")
                        max_y = app_df["delta_mb"].max() * 1.1 if not app_df.empty and app_df["delta_mb"].max() > 0 else 0.01
                        fig_lifecycle.add_trace(go.Scatter(
                            x=[start, end, end, start, start],
                            y=[0, 0, max_y, max_y, 0],
                            fill="toself", fillcolor=color, opacity=0.15,
                            line=dict(width=0), mode="lines",
                            showlegend=False), row=idx, col=1)
                    fig_lifecycle.update_yaxes(title_text="MB", row=idx, col=1)
                fig_lifecycle.update_layout(
                    title="App Lifecycle vs Network Traffic (shaded = foreground per display)",
                    height=180 * n_lc + 80)
    children.append(_full(_synced_graph(fig_lifecycle, 180 * 6 + 80, zoom_range)))

    return html.Div(children)


def _build_vehicle_tab(dfs, zoom_range=None):
    children = []
    fig_speed = go.Figure()
    fig_gear = go.Figure()

    # Collect VHAL property data from multiple sources
    vhal_rows = []

    # New format: VHAL_ValueChanged (single change events)
    df_vhal_single = dfs.get("VHAL_ValueChanged", pd.DataFrame())
    if not df_vhal_single.empty:
        for _, row in df_vhal_single.iterrows():
            vhal_rows.append({
                "timestampMillis": row.get("timestamp"),
                "property": row.get("property", ""),
                "previous": row.get("previous"),
                "current": row.get("current"),
            })

    # New format: VHAL_SampledBatch (batched sampled values)
    df_vhal_batch = dfs.get("VHAL_SampledBatch", pd.DataFrame())
    if not df_vhal_batch.empty:
        for _, row in df_vhal_batch.iterrows():
            schema = row.get("sampleSchema", ["ts", "property", "value"])
            samples = row.get("samples", [])
            if samples:
                for sample in samples:
                    r = dict(zip(schema, sample))
                    vhal_rows.append({
                        "timestampMillis": r.get("ts"),
                        "property": r.get("property", ""),
                        "previous": None,
                        "current": r.get("value"),
                    })

    # Legacy format: VHAL_ValuesChanged (batched change events)
    df_vhal_legacy = dfs.get("VHAL_ValuesChanged", pd.DataFrame())
    if not df_vhal_legacy.empty and "changes" in df_vhal_legacy.columns:
        for _, row in df_vhal_legacy.iterrows():
            changes = row.get("changes", [])
            schema = row.get("sampleSchema", ["timestampMillis", "property", "previous", "current"])
            if changes:
                for change in changes:
                    r = dict(zip(schema, change))
                    vhal_rows.append(r)

    if vhal_rows:
        df_props = pd.DataFrame(vhal_rows)
        if "timestampMillis" in df_props.columns:
            df_props["datetime"] = pd.to_datetime(df_props["timestampMillis"], unit="ms") + TZ_OFFSET

        df_speed_data = df_props[df_props["property"] == "PERF_VEHICLE_SPEED"].copy()
        if not df_speed_data.empty:
            df_speed_data["current"] = pd.to_numeric(df_speed_data["current"], errors="coerce") * 3.6
            fig_speed = px.line(df_speed_data, x="datetime", y="current",
                                title="Vehicle Speed", markers=True)
            fig_speed.update_layout(yaxis_title="km/h")

        df_gear_data = df_props[df_props["property"] == "GEAR_SELECTION"].copy()
        if not df_gear_data.empty:
            gear_map = {1: "P", 2: "R", 4: "N", 8: "D"}
            df_gear_data["gear"] = df_gear_data["current"].apply(
                lambda x: gear_map.get(x, str(x)) if x is not None else "?")
            fig_gear = px.scatter(df_gear_data, x="datetime", y="gear",
                                  title="Gear Selection", text="gear")
            fig_gear.update_traces(marker=dict(size=12))

    children.append(_row(_synced_graph(fig_speed, 350, zoom_range), _synced_graph(fig_gear, 250, zoom_range)))

    # GPS / Location
    df_loc = dfs.get("Location_Position", pd.DataFrame())
    if not df_loc.empty:
        loc_exp = expand_samples(df_loc)
        if "latitude" in loc_exp.columns and "longitude" in loc_exp.columns and "datetime" in loc_exp.columns:
            loc_exp["latitude"] = pd.to_numeric(loc_exp["latitude"], errors="coerce")
            loc_exp["longitude"] = pd.to_numeric(loc_exp["longitude"], errors="coerce")
            loc_valid = loc_exp.dropna(subset=["latitude", "longitude"])
            loc_valid = loc_valid[(loc_valid["latitude"].abs() > 0.01) | (loc_valid["longitude"].abs() > 0.01)]

            if not loc_valid.empty:
                # Map scatter
                fig_map = px.scatter_map(
                    loc_valid, lat="latitude", lon="longitude",
                    color_discrete_sequence=["#0f9b8e"],
                    title="GPS Track", zoom=12,
                )
                fig_map.update_layout(map_style="open-street-map")
                children.append(_full(_graph(fig_map, 500)))

            # Speed over time
            if "speedMps" in loc_exp.columns:
                loc_exp["speed_kmh"] = pd.to_numeric(loc_exp["speedMps"], errors="coerce") * 3.6
                fig_speed_gps = px.line(loc_exp.dropna(subset=["speed_kmh"]),
                                        x="datetime", y="speed_kmh",
                                        title="GPS Speed (km/h)", markers=True)
                fig_speed_gps.update_traces(line=dict(color="#0f9b8e"))

                fig_alt = go.Figure()
                if "altitude" in loc_exp.columns:
                    loc_exp["altitude"] = pd.to_numeric(loc_exp["altitude"], errors="coerce")
                    fig_alt = px.line(loc_exp.dropna(subset=["altitude"]),
                                     x="datetime", y="altitude",
                                     title="Altitude (m)", markers=True)
                    fig_alt.update_traces(line=dict(color="#5b8def"))

                children.append(_row(_synced_graph(fig_speed_gps, 300, zoom_range), _synced_graph(fig_alt, 300, zoom_range)))

            # Accuracy
            if "accuracyMeters" in loc_exp.columns:
                loc_exp["accuracyMeters"] = pd.to_numeric(loc_exp["accuracyMeters"], errors="coerce")
                fig_acc = px.line(loc_exp.dropna(subset=["accuracyMeters"]),
                                 x="datetime", y="accuracyMeters",
                                 title="GPS Accuracy (m)", markers=True)
                fig_acc.update_traces(line=dict(color="#f39c12"))
                children.append(_full(_synced_graph(fig_acc, 250, zoom_range)))

    # CPU Usage
    cpu_exp = _get_cpu_df(dfs)
    if not cpu_exp.empty:
        if "usagePct" in cpu_exp.columns and "datetime" in cpu_exp.columns:
            cpu_exp["usagePct"] = pd.to_numeric(cpu_exp["usagePct"], errors="coerce")
            fig_cpu = px.line(cpu_exp.dropna(subset=["usagePct"]),
                              x="datetime", y="usagePct",
                              title="System CPU Usage (%)", markers=True)
            fig_cpu.update_traces(line=dict(color="#e74c3c"))
            fig_cpu.add_hline(y=100, line_dash="dash", line_color="gray")
            children.append(_full(_synced_graph(fig_cpu, 350, zoom_range)))

            if "loadAvg1min" in cpu_exp.columns:
                cpu_exp["loadAvg1min"] = pd.to_numeric(cpu_exp["loadAvg1min"], errors="coerce")
                fig_load = px.line(cpu_exp.dropna(subset=["loadAvg1min"]),
                                   x="datetime", y="loadAvg1min",
                                   title="1-min Load Average", markers=True)
                fig_load.update_traces(line=dict(color="#9b59b6"))
                children.append(_full(_synced_graph(fig_load, 250, zoom_range)))

    # Self-Monitor
    df_self = dfs.get("SelfMonitor_Usage", pd.DataFrame())
    if not df_self.empty:
        self_exp = expand_samples(df_self)
        if "datetime" in self_exp.columns:
            sm_figs = []
            if "cpuPct" in self_exp.columns:
                self_exp["cpuPct"] = pd.to_numeric(self_exp["cpuPct"], errors="coerce")
                fig_sm_cpu = px.line(self_exp.dropna(subset=["cpuPct"]),
                                     x="datetime", y="cpuPct",
                                     title="DataCollector CPU (%)", markers=True)
                fig_sm_cpu.update_traces(line=dict(color="#e74c3c"))
                sm_figs.append(_synced_graph(fig_sm_cpu, 300, zoom_range))
            if "memMb" in self_exp.columns:
                self_exp["memMb"] = pd.to_numeric(self_exp["memMb"], errors="coerce")
                fig_sm_mem = px.line(self_exp.dropna(subset=["memMb"]),
                                     x="datetime", y="memMb",
                                     title="DataCollector Memory (MB)", markers=True)
                fig_sm_mem.update_traces(line=dict(color="#0f9b8e"))
                sm_figs.append(_synced_graph(fig_sm_mem, 300, zoom_range))
            if len(sm_figs) >= 2:
                children.append(_row(sm_figs[0], sm_figs[1]))
            elif sm_figs:
                children.append(_full(sm_figs[0]))

            if "threadCount" in self_exp.columns:
                self_exp["threadCount"] = pd.to_numeric(self_exp["threadCount"], errors="coerce")
                fig_threads = px.line(self_exp.dropna(subset=["threadCount"]),
                                      x="datetime", y="threadCount",
                                      title="DataCollector Threads", markers=True)
                fig_threads.update_traces(line=dict(color="#5b8def"))
                children.append(_full(_synced_graph(fig_threads, 250, zoom_range)))

    return html.Div(children)


def _build_audio_media_tab(dfs, zoom_range=None):
    children = []

    # --- Audio Volume Groups Over Time ---
    df_audio_snap = dfs.get("Audio_Snapshot", pd.DataFrame())
    df_audio_change = dfs.get("Audio_StateChanged", pd.DataFrame())

    # Combine volume data from snapshots and state changes
    vol_rows = []
    if not df_audio_snap.empty:
        for _, row in df_audio_snap.iterrows():
            groups = row.get("carVolumeGroups")
            if isinstance(groups, dict):
                for grp, val in groups.items():
                    if isinstance(val, str) and "/" in val:
                        current, maximum = val.split("/", 1)
                        try:
                            vol_rows.append({"datetime": row.get("datetime"), "group": grp,
                                             "volume": int(current), "max": int(maximum)})
                        except (ValueError, TypeError):
                            pass

    if not df_audio_change.empty:
        for _, row in df_audio_change.iterrows():
            current = row.get("current")
            if isinstance(current, dict):
                groups = current.get("carVolumeGroups") if isinstance(current, dict) else None
                if isinstance(groups, dict):
                    for grp, val in groups.items():
                        if isinstance(val, str) and "/" in val:
                            cur_v, max_v = val.split("/", 1)
                            try:
                                vol_rows.append({"datetime": row.get("datetime"), "group": grp,
                                                 "volume": int(cur_v), "max": int(max_v)})
                            except (ValueError, TypeError):
                                pass

    if vol_rows:
        df_vol = pd.DataFrame(vol_rows)
        if "datetime" in df_vol.columns and not df_vol.empty:
            fig_vol = go.Figure()
            for grp in sorted(df_vol["group"].unique()):
                grp_data = df_vol[df_vol["group"] == grp].sort_values("datetime")
                fig_vol.add_trace(go.Scatter(
                    x=grp_data["datetime"], y=grp_data["volume"],
                    mode="lines+markers", name=grp.replace("_", " ").title(),
                ))
            fig_vol.update_layout(title="Audio Volume Groups Over Time", yaxis_title="Level")
            children.append(_full(_synced_graph(fig_vol, 350, zoom_range)))

    # --- Audio Output Devices ---
    if not df_audio_snap.empty and "outputDevices" in df_audio_snap.columns:
        device_counts = defaultdict(int)
        for _, row in df_audio_snap.iterrows():
            devices = row.get("outputDevices")
            if isinstance(devices, list):
                for d in devices:
                    device_counts[str(d)] += 1
        if device_counts:
            fig_devices = go.Figure()
            names = list(device_counts.keys())
            counts = [device_counts[n] for n in names]
            fig_devices.add_trace(go.Bar(x=names, y=counts, marker_color="#0f9b8e"))
            fig_devices.update_layout(title="Audio Output Devices (Snapshot Count)")
            children.append(_full(_graph(fig_devices, 300)))

    # --- Media Playback State ---
    df_media_state = dfs.get("Media_StateChanged", pd.DataFrame())
    if not df_media_state.empty and "datetime" in df_media_state.columns:
        fig_media_state = go.Figure()
        states = df_media_state.apply(
            lambda r: str(r.get("state", r.get("current", "?"))), axis=1
        )
        packages = df_media_state.apply(
            lambda r: _short_name(str(r.get("package", ""))), axis=1
        )
        fig_media_state.add_trace(go.Scatter(
            x=df_media_state["datetime"], y=states,
            mode="markers+lines", name="State",
            text=packages, marker=dict(size=8, color="#5b8def"),
        ))
        fig_media_state.update_layout(title="Media Playback State", yaxis_title="State")
        children.append(_full(_synced_graph(fig_media_state, 300, zoom_range)))

    # --- Media Track Changes ---
    df_media_track = dfs.get("Media_TrackChanged", pd.DataFrame())
    if not df_media_track.empty and "datetime" in df_media_track.columns:
        # Show track change events as vertical markers
        fig_tracks = go.Figure()
        labels = df_media_track.apply(
            lambda r: _short_name(str(
                (r.get("current") or {}).get("package", "") if isinstance(r.get("current"), dict) else r.get("current.package", "")
            )), axis=1
        )
        fig_tracks.add_trace(go.Scatter(
            x=df_media_track["datetime"], y=[1] * len(df_media_track),
            mode="markers", name="Track Change",
            text=labels, marker=dict(size=10, color="#e74c3c", symbol="diamond"),
        ))
        fig_tracks.update_layout(
            title="Media Track Changes",
            yaxis=dict(showticklabels=False, range=[0, 2]),
        )
        children.append(_full(_synced_graph(fig_tracks, 200, zoom_range)))

    # --- Media Position Jumps ---
    df_pos = dfs.get("Media_PositionJumped", pd.DataFrame())
    if not df_pos.empty and "datetime" in df_pos.columns:
        if "deltaMs" in df_pos.columns:
            df_pos["deltaMs"] = pd.to_numeric(df_pos["deltaMs"], errors="coerce")
            df_pos["deltaSec"] = df_pos["deltaMs"] / 1000
            fig_jumps = px.scatter(df_pos.dropna(subset=["deltaSec"]),
                                   x="datetime", y="deltaSec",
                                   color="direction" if "direction" in df_pos.columns else None,
                                   title="Media Position Jumps (seconds)")
            fig_jumps.update_traces(marker=dict(size=10))
            children.append(_full(_synced_graph(fig_jumps, 300, zoom_range)))

    # --- Navigation Focus ---
    df_nav = dfs.get("Navigation_FocusChanged", pd.DataFrame())
    if not df_nav.empty and "datetime" in df_nav.columns:
        fig_nav = go.Figure()
        nav_labels = df_nav.apply(
            lambda r: f"{r.get('reason', '?')}: {_short_name(str(r.get('currentOwner', '')))}", axis=1
        )
        source_types = df_nav.apply(lambda r: str(r.get("currentSourceType", "?")), axis=1)
        fig_nav.add_trace(go.Scatter(
            x=df_nav["datetime"], y=source_types,
            mode="markers+lines", name="Nav Source",
            text=nav_labels, marker=dict(size=10, color="#f39c12"),
        ))
        fig_nav.update_layout(title="Navigation Focus Changes", yaxis_title="Source Type")
        children.append(_full(_synced_graph(fig_nav, 250, zoom_range)))

    if not children:
        children.append(html.P("No audio/media/navigation data available.", style={"color": "#888"}))

    return html.Div(children)


def _build_display_power_tab(dfs, zoom_range=None):
    children = []

    # --- Display State Changes (Gantt-like) ---
    df_disp_state = dfs.get("Display_StateChanged", pd.DataFrame())
    df_disp_snap = dfs.get("Display_StateSnapshot", pd.DataFrame())

    # Combine state data
    state_rows = []
    for df_src in [df_disp_snap, df_disp_state]:
        if df_src is None or df_src.empty:
            continue
        for _, row in df_src.iterrows():
            dt = row.get("datetime")
            current = row.get("current")
            if isinstance(current, dict):
                for display_name, state in current.items():
                    state_rows.append({"datetime": dt, "display": display_name, "state": str(state)})
            else:
                # Try flattened keys like "current.center"
                for col in row.index:
                    if col.startswith("current."):
                        display_name = col.replace("current.", "")
                        state_rows.append({"datetime": dt, "display": display_name, "state": str(row[col])})

    if state_rows:
        df_states = pd.DataFrame(state_rows).sort_values("datetime")
        # Normalize standby variants to "standby"
        df_states["state"] = df_states["state"].apply(
            lambda s: "standby" if "standby" in s.lower() else s.lower()
        )
        displays_found = sorted(df_states["display"].unique())
        state_colors = {"on": "#2ecc71", "off": "#e74c3c", "standby": "#f39c12"}

        fig_disp = go.Figure()
        legend_added = set()

        for idx, disp in enumerate(displays_found):
            disp_data = df_states[df_states["display"] == disp].reset_index(drop=True)
            for i in range(len(disp_data)):
                state = disp_data.iloc[i]["state"]
                start = disp_data.iloc[i]["datetime"]
                if i + 1 < len(disp_data):
                    end = _clamp_to_active(start, disp_data.iloc[i + 1]["datetime"])
                else:
                    end = _clamp_to_active(start, MAX_DT)
                dur_s = (end - start).total_seconds()
                if dur_s > 0:
                    color = state_colors.get(state, "#888")
                    fig_disp.add_shape(
                        type="rect", x0=start, x1=end,
                        y0=idx - 0.4, y1=idx + 0.4,
                        fillcolor=color, opacity=0.85,
                        line=dict(width=0.5, color="#fff"),
                    )
                    if state not in legend_added:
                        legend_added.add(state)
                        fig_disp.add_trace(go.Scatter(
                            x=[None], y=[None], mode="markers",
                            marker=dict(size=12, color=color, symbol="square"),
                            name=state.title(), showlegend=True,
                        ))

        fig_disp.update_layout(
            title="Display States Over Time",
            height=100 * len(displays_found) + 80,
            yaxis=dict(
                tickvals=list(range(len(displays_found))),
                ticktext=[d.title() for d in displays_found],
                range=[-0.5, len(displays_found) - 0.5],
            ),
            xaxis=dict(type="date"),
            margin=dict(l=100, r=30, t=80, b=60),
        )
        children.append(_full(_synced_graph(fig_disp, 100 * len(displays_found) + 80, zoom_range)))

    # --- Display Brightness ---
    df_bright = dfs.get("Display_BrightnessSnapshot", pd.DataFrame())
    if not df_bright.empty and "datetime" in df_bright.columns:
        fig_bright = go.Figure()
        brightness_cols = [c for c in df_bright.columns if c not in (
            "timestamp", "datetime", "session", "signalId", "trigger", "actionName"
        )]
        for col in brightness_cols:
            vals = pd.to_numeric(df_bright[col], errors="coerce")
            if vals.notna().any():
                fig_bright.add_trace(go.Scatter(
                    x=df_bright["datetime"], y=vals,
                    mode="lines+markers", name=col.title(),
                ))
        fig_bright.update_layout(title="Display Brightness Levels", yaxis_title="Level")
        children.append(_full(_synced_graph(fig_bright, 300, zoom_range)))

    # --- Power State Changes (includes Boot + State transitions) ---
    df_power = dfs.get("Power_StateChanged", pd.DataFrame())
    df_boot = dfs.get("Power_Boot", pd.DataFrame())
    fig_power = go.Figure()
    power_state_rows = []

    # Collect Power_StateChanged events
    if not df_power.empty and "datetime" in df_power.columns:
        for _, row in df_power.iterrows():
            state = str(row.get("state", row.get("current", "?")))
            power_state_rows.append({"datetime": row["datetime"], "state": state, "type": "state"})

    # Collect Power_Boot events
    if not df_boot.empty and "datetime" in df_boot.columns:
        for _, row in df_boot.iterrows():
            ignition = str(row.get("ignitionState", "?"))
            uptime_s = int(row.get("uptimeMs", 0)) / 1000
            power_state_rows.append({
                "datetime": row["datetime"],
                "state": f"BOOT (ign={ignition}, uptime={uptime_s:.0f}s)",
                "type": "boot",
            })

    if power_state_rows:
        df_pwr = pd.DataFrame(power_state_rows).sort_values("datetime")
        # Map states to numeric y values for a cleaner timeline
        state_order = ["BOOT", "ON", "PRE_SHUTDOWN_PREPARE", "SHUTDOWN_PREPARE",
                       "SUSPEND_ENTER", "POST_SUSPEND_ENTER", "SUSPEND_EXIT",
                       "SHUTDOWN_ENTER", "POST_SHUTDOWN_ENTER"]
        state_y = {s: i for i, s in enumerate(state_order)}

        # State transitions
        state_entries = df_pwr[df_pwr["type"] == "state"]
        if not state_entries.empty:
            y_vals = [state_y.get(s, len(state_order)) for s in state_entries["state"]]
            fig_power.add_trace(go.Scatter(
                x=state_entries["datetime"], y=y_vals,
                mode="lines+markers", name="Power State",
                line=dict(color="#e74c3c", width=2),
                marker=dict(size=8),
                text=state_entries["state"], hoverinfo="text+x",
            ))

        # Boot events (highlighted)
        boot_entries = df_pwr[df_pwr["type"] == "boot"]
        if not boot_entries.empty:
            fig_power.add_trace(go.Scatter(
                x=boot_entries["datetime"], y=[state_y.get("BOOT", 0)] * len(boot_entries),
                mode="markers", name="Boot",
                marker=dict(size=16, color="#2ecc71", symbol="star"),
                text=boot_entries["state"], hoverinfo="text+x",
            ))

        fig_power.update_layout(
            title="Power State & Boot Events",
            yaxis=dict(tickvals=list(range(len(state_order))), ticktext=state_order),
        )
        children.append(_full(_synced_graph(fig_power, 350, zoom_range)))

    # --- Vehicle Info (display as info cards) ---
    df_vinfo = dfs.get("Vehicle_Info", pd.DataFrame())
    df_sysinfo = dfs.get("System_Info", pd.DataFrame())
    info_items = []
    if not df_vinfo.empty:
        last = df_vinfo.iloc[-1]
        info_items.append(f"**Vehicle:** {last.get('make', '?')} {last.get('model', '?')} MY{last.get('model_year', '?')}")
    if not df_sysinfo.empty:
        last = df_sysinfo.iloc[-1]
        info_items.append(f"**Android:** {last.get('androidVersion', '?')} (SDK {last.get('sdkLevel', '?')})")
        info_items.append(f"**Security Patch:** {last.get('securityPatch', '?')}")
        build = str(last.get('buildDisplay', ''))
        if len(build) > 60:
            build = build[:60] + "..."
        info_items.append(f"**Build:** {build}")
    if info_items:
        info_div = html.Div([
            html.H4("System Information", style={"margin": "0 0 8px 0", "color": PDS_PRIMARY}),
            *[html.P(dcc.Markdown(item), style={"margin": "2px 0", "fontSize": "14px"}) for item in info_items],
        ], style={**CARD_STYLE, "textAlign": "left", "flex": "none", "width": "100%", "marginBottom": "16px"})
        children.append(info_div)

    # --- Connectivity Transport Changes ---
    df_conn = dfs.get("Connectivity_TransportChanged", pd.DataFrame())
    if not df_conn.empty and "datetime" in df_conn.columns:
        fig_conn = go.Figure()
        prev_vals = df_conn.apply(lambda r: str(r.get("previous", "?")), axis=1)
        curr_vals = df_conn.apply(lambda r: str(r.get("current", "?")), axis=1)
        labels = [f"{p} → {c}" for p, c in zip(prev_vals, curr_vals)]
        fig_conn.add_trace(go.Scatter(
            x=df_conn["datetime"], y=curr_vals,
            mode="markers+lines", name="Transport",
            marker=dict(size=12, color="#f39c12", symbol="diamond"),
            text=labels, hoverinfo="text+x",
        ))
        fig_conn.update_layout(title="Connectivity Transport Changes", yaxis_title="Transport")
        children.append(_full(_synced_graph(fig_conn, 250, zoom_range)))

    # --- Bluetooth ---
    df_bt = dfs.get("Bluetooth_StateChanged", pd.DataFrame())
    if not df_bt.empty and "datetime" in df_bt.columns:
        fig_bt = go.Figure()
        bt_labels = df_bt.apply(
            lambda r: str(r.get("current", r.get("state", "?"))), axis=1
        )
        fig_bt.add_trace(go.Scatter(
            x=df_bt["datetime"], y=bt_labels,
            mode="markers+lines", name="BT State",
            marker=dict(size=10, color="#5b8def"),
        ))
        fig_bt.update_layout(title="Bluetooth State", yaxis_title="State")
        children.append(_full(_synced_graph(fig_bt, 250, zoom_range)))

    # --- Hotspot State ---
    df_hs = dfs.get("Hotspot_StateChanged", pd.DataFrame())
    if not df_hs.empty and "datetime" in df_hs.columns:
        fig_hs = go.Figure()
        hs_enabled = df_hs.apply(lambda r: 1 if r.get("enabled") else 0, axis=1)
        fig_hs.add_trace(go.Scatter(
            x=df_hs["datetime"], y=hs_enabled,
            mode="lines+markers", name="Hotspot",
            line=dict(color="#9b59b6", width=2),
            marker=dict(size=8),
        ))
        fig_hs.update_layout(
            title="Hotspot State", yaxis_title="Enabled",
            yaxis=dict(tickvals=[0, 1], ticktext=["Off", "On"]),
        )
        children.append(_full(_synced_graph(fig_hs, 200, zoom_range)))

    if not children:
        children.append(html.P("No display/power data available.", style={"color": "#888"}))

    return html.Div(children)


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("[Dashboard] Starting at http://127.0.0.1:8050")
    app.run(debug=True, host="127.0.0.1", port=8050)
