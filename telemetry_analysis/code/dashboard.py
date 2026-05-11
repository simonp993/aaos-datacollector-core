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
from datetime import datetime
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
    1: "Instrument Cluster",
    2: "Passenger Screen",
    3: "Rear Passenger",
}

DISPLAY_COLORS = {
    0: "#0f9b8e",
    1: "#f39c12",
    2: "#5b8def",
    3: "#9b59b6",
}

DISPLAY_RESOLUTIONS = {
    0: (1920, 720),
    1: (1920, 1080),
    2: (1920, 720),
    3: (1280, 768),
}

TOUCH_EXCLUDED_DISPLAYS = {1}

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
            df["datetime"] = pd.to_datetime(df["timestamp"], unit="ms")
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
        result["datetime"] = pd.to_datetime(result["timestampMillis"], unit="ms")
        result = result.sort_values("datetime").reset_index(drop=True)
    return result


def _short_name(pkg: str) -> str:
    if not pkg or pkg == "nan" or pkg == "None":
        return "unknown"
    return pkg


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
MIN_DT = pd.to_datetime(MIN_TS, unit="ms")
MAX_DT = pd.to_datetime(MAX_TS, unit="ms")

# Extract all apps from focus data
df_focus_global = DFS.get("App_FocusChanged", pd.DataFrame())
ALL_APPS = []
if not df_focus_global.empty:
    pkgs = df_focus_global.apply(
        lambda r: str(r.get("current.package", "") or ""), axis=1
    )
    ALL_APPS = sorted(set(p for p in pkgs if p and p != "nan"))

ALL_DISPLAYS = sorted(
    d for d in range(4)
    if not df_focus_global.empty
)

# Extract all trigger types
ALL_TRIGGERS = sorted(set(
    e.get("payload", {}).get("trigger", "") for e in RAW_EVENTS
    if e.get("payload", {}).get("trigger")
))

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
    (pd.to_datetime(s, unit="ms"), pd.to_datetime(e, unit="ms"))
    for s, e in SESSION_RANGES
]


def _session_end_for(dt):
    """Return the end of the session containing *dt*, or *dt* itself."""
    for s_dt, e_dt in SESSION_DT_RANGES:
        if s_dt <= dt <= e_dt:
            return e_dt
    return dt


def _build_range_selector_fig():
    """Build a plotly figure with session bars and a built-in rangeslider for time selection."""
    fig = go.Figure()
    # Add session bars as visual indicators in the main area and rangeslider
    for i, (s, e) in enumerate(SESSION_RANGES):
        s_dt = pd.to_datetime(s, unit="ms")
        e_dt = pd.to_datetime(e, unit="ms")
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
    min_dt = pd.to_datetime(MIN_TS, unit="ms")
    max_dt = pd.to_datetime(MAX_TS, unit="ms")
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
        margin=dict(l=50, r=30, t=50, b=40),
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
    Input("section-tabs", "value"),
    Input("graph-zoom", "data"),
)
def update_dashboard(relayout_data, displays, apps, triggers, active_tab, zoom_data):
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
    s_dt = pd.to_datetime(start_ts, unit="ms")
    e_dt = pd.to_datetime(end_ts, unit="ms")
    range_label = f"Selected: {s_dt.strftime('%H:%M:%S')} — {e_dt.strftime('%H:%M:%S')}"

    events, dfs = _filter_events([start_ts, end_ts])
    displays = displays or ALL_DISPLAYS
    apps = apps or []
    triggers = triggers or []

    # Apply trigger filter
    if triggers:
        events = [e for e in events if e.get("payload", {}).get("trigger", "") in triggers]
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
        content = _build_network_tab(dfs, apps, zoom_range)
    elif active_tab == "vehicle":
        content = _build_vehicle_tab(dfs, zoom_range)
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
        x_min = pd.to_datetime(min(all_ts), unit="ms")
        x_max = pd.to_datetime(max(all_ts), unit="ms")
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
        all_pkgs = sorted(set(_short_name(str(p)) for p in df_focus["current_pkg"] if p and str(p) != "nan"))
        app_palette = px.colors.qualitative.Plotly + px.colors.qualitative.Set2 + px.colors.qualitative.Pastel
        app_color_map = {pkg: app_palette[i % len(app_palette)] for i, pkg in enumerate(all_pkgs)}

        fig_app = go.Figure()
        legend_added = set()

        for idx, did in enumerate(active_displays):
            df_d = df_focus[df_focus["display_id"] == did].reset_index(drop=True)
            if df_d.empty:
                continue
            for i in range(len(df_d)):
                pkg = _short_name(str(df_d.iloc[i]["current_pkg"]))
                start = df_d.iloc[i]["datetime"]
                session_end = _session_end_for(start)
                # End is the next event OR session boundary, whichever comes first
                if i + 1 < len(df_d):
                    next_dt = df_d.iloc[i + 1]["datetime"]
                    end = min(next_dt, session_end)
                else:
                    end = session_end
                dur_s = (end - start).total_seconds()
                if 0 < dur_s < 86400:
                    color = app_color_map.get(pkg, "#888")
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
            legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
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
                legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
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
                legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
                xaxis=dict(range=[x_min, x_max]),
            )
            children.append(_full(_timeline_graph(fig_dropped, 250, zoom_range)))

    # --- 4) Available Memory ---
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_mem.empty:
        mem_exp = expand_samples(df_mem)
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
            if "totalMem" in df_mem.columns:
                total_vals = df_mem["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_gb = int(total_vals.iloc[0]) / (1024 * 1024 * 1000)
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
                legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
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
            if not apps_data or not isinstance(apps_data, list):
                continue
            for a in apps_data:
                if not isinstance(a, dict):
                    continue
                pkgs = a.get("packages", [])
                uid = a.get("uid", 0)
                label = _short_name(pkgs[0]) if pkgs else f"uid:{uid}"
                app_traffic_rows.append({
                    "datetime": dt, "timestamp": ts, "app": label,
                    "rx": a.get("rxBytes", 0), "tx": a.get("txBytes", 0),
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
                    legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
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
            dt = pd.to_datetime(ts, unit="ms")
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
            event_rows.append({"datetime": pd.to_datetime(ts, unit="ms")})
    if event_rows:
        df_rate = pd.DataFrame(event_rows)
        df_rate["time_bin"] = df_rate["datetime"].dt.floor("1min")
        rate = df_rate.groupby("time_bin").size().reset_index(name="events_per_min")

        # Event Rate vs Available Memory
        df_mem = dfs.get("Memory_Usage", pd.DataFrame())
        fig_ev_mem = go.Figure()
        if not df_mem.empty:
            mem_exp = expand_samples(df_mem)
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
        df_cpu = dfs.get("CPU_Usage", pd.DataFrame())
        if not df_cpu.empty:
            cpu_exp = expand_samples(df_cpu)
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
    all_pkgs = sorted(set(_short_name(str(p)) for p in df_physical["current_pkg"] if p and str(p) != "nan"))
    app_palette = px.colors.qualitative.Plotly + px.colors.qualitative.Set2 + px.colors.qualitative.Pastel
    app_color_map = {pkg: app_palette[i % len(app_palette)] for i, pkg in enumerate(all_pkgs)}
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
            session_end = _session_end_for(start)
            if i + 1 < len(df_disp):
                next_dt = df_disp.iloc[i + 1]["datetime"]
                end = min(next_dt, session_end)
            else:
                end = session_end
            dur_s = (end - start).total_seconds()
            if 0 < dur_s < 86400:
                color = app_color_map.get(short, "#888")
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
        legend=dict(orientation="h", y=-0.15, x=0, font=dict(size=10)),
        margin=dict(l=120, r=30, t=50, b=60),
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
                margin=dict(l=60, r=60, t=50, b=30),
            )
            fig_h = max(400, int(res[1] / res[0] * 1400) + 80)
            children.append(_full(_graph(fig, fig_h)))

    return html.Div(children)


def _build_performance_tab(dfs, zoom_range=None):
    children = []

    # Memory
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    fig_mem = go.Figure()
    if not df_mem.empty:
        mem_exp = expand_samples(df_mem)
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            mem_exp["availGb"] = pd.to_numeric(mem_exp["availMb"], errors="coerce") / 1000
            fig_mem.add_trace(go.Scatter(x=mem_exp["datetime"], y=mem_exp["availGb"],
                                         mode="lines+markers", name="Available",
                                         line=dict(color="#0f9b8e")))
            total_gb = None
            if "totalMem" in df_mem.columns:
                total_vals = df_mem["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_gb = int(total_vals.iloc[0]) / (1024 * 1024 * 1000)
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
    df_cpu = dfs.get("CPU_Usage", pd.DataFrame())
    if not df_cpu.empty:
        cpu_exp = expand_samples(df_cpu)
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


def _build_network_tab(dfs, apps, zoom_range=None):
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

    # Total traffic
    df_wifi = dfs.get("Network_WifiTotal", pd.DataFrame())
    fig_traffic = go.Figure()
    if not df_wifi.empty and "datetime" in df_wifi.columns:
        for col, name, color in [("rxBytes", "WiFi RX", "#0f9b8e"), ("txBytes", "WiFi TX", "#5b8def")]:
            if col in df_wifi.columns:
                fig_traffic.add_trace(go.Scatter(
                    x=df_wifi["datetime"], y=df_wifi[col] / (1024 * 1024),
                    mode="lines", name=name, line=dict(color=color)))
        fig_traffic.update_layout(title="Network Traffic Over Time", yaxis_title="MB")

    # Per-app traffic
    df_perapp = dfs.get("Network_PerAppTraffic", pd.DataFrame())
    fig_perapp = go.Figure()
    if not df_perapp.empty and "apps" in df_perapp.columns:
        # Filter by zoom range if set
        df_pa = df_perapp
        if zoom_range and "datetime" in df_pa.columns:
            z_start = pd.to_datetime(zoom_range[0])
            z_end = pd.to_datetime(zoom_range[1])
            df_pa = df_pa[(df_pa["datetime"] >= z_start) & (df_pa["datetime"] <= z_end)]

        # rxBytes/txBytes are already per-interval deltas from the collector — just sum them
        app_traffic_rows = []
        for _, row in df_pa.sort_values("timestamp").iterrows():
            apps_data = row.get("apps", [])
            if not apps_data:
                continue
            for a in apps_data:
                if not isinstance(a, dict):
                    continue
                pkgs = a.get("packages", [])
                label = pkgs[0] if pkgs else f"uid:{a.get('uid', 0)}"
                if apps and label not in apps:
                    continue
                app_traffic_rows.append({"app": label,
                                         "rx": a.get("rxBytes", 0), "tx": a.get("txBytes", 0)})

        if app_traffic_rows:
            df_at = pd.DataFrame(app_traffic_rows)
            app_sums = df_at.groupby("app")[["rx", "tx"]].sum()

            traffic_data = []
            for label in app_sums.index:
                rx_mb = app_sums.loc[label, "rx"] / (1024 * 1024)
                tx_mb = app_sums.loc[label, "tx"] / (1024 * 1024)
                if rx_mb + tx_mb > 0.001:
                    traffic_data.append({"App": _short_name(label), "RX (MB)": rx_mb, "TX (MB)": tx_mb})
            if traffic_data:
                df_t = pd.DataFrame(traffic_data).sort_values("RX (MB)", ascending=True)
                fig_perapp.add_trace(go.Bar(y=df_t["App"], x=df_t["RX (MB)"], name="RX",
                                            orientation="h", marker_color="#0f9b8e"))
                fig_perapp.add_trace(go.Bar(y=df_t["App"], x=df_t["TX (MB)"], name="TX",
                                            orientation="h", marker_color="#5b8def"))
                fig_perapp.update_layout(title="Per-App Traffic (MB)", barmode="group",
                                         height=max(300, len(df_t) * 45), xaxis_title="MB")

    children.append(_row(_synced_graph(fig_traffic, 350, zoom_range), _graph(fig_perapp, 350)))

    # App Lifecycle vs Network Traffic
    df_focus_lc = dfs.get("App_FocusChanged", pd.DataFrame())
    fig_lifecycle = go.Figure()
    if not df_perapp.empty and "apps" in df_perapp.columns and not df_focus_lc.empty:
        traffic_rows = []
        for _, row in df_perapp.sort_values("timestamp").iterrows():
            dt = row.get("datetime")
            ts = row.get("timestamp")
            apps_data = row.get("apps", [])
            if not apps_data or not isinstance(apps_data, list):
                continue
            for a in apps_data:
                if not isinstance(a, dict):
                    continue
                pkgs = a.get("packages", [])
                label = pkgs[0] if pkgs else f"uid:{a.get('uid', 0)}"
                if apps and label not in apps:
                    continue
                traffic_rows.append({"datetime": dt, "timestamp": ts, "app": label,
                                     "rx": a.get("rxBytes", 0), "tx": a.get("txBytes", 0)})
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
    df_vhal = dfs.get("VHAL_ValuesChanged", pd.DataFrame())
    fig_speed = go.Figure()
    fig_gear = go.Figure()

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

            df_speed_data = df_props[df_props["property"] == "PERF_VEHICLE_SPEED"].copy()
            if not df_speed_data.empty:
                df_speed_data["current"] = pd.to_numeric(df_speed_data["current"], errors="coerce")
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
    df_cpu = dfs.get("CPU_Usage", pd.DataFrame())
    if not df_cpu.empty:
        cpu_exp = expand_samples(df_cpu)
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


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("[Dashboard] Starting at http://127.0.0.1:8050")
    app.run(debug=True, host="127.0.0.1", port=8050)
