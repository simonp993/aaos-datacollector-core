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

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
from plotly.subplots import make_subplots

from dash import Dash, Input, Output, State, callback, ctx, dcc, html

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

PLOT_TEMPLATE = "plotly_white"
CARD_STYLE = {
    "backgroundColor": "#ffffff",
    "borderRadius": "8px",
    "padding": "16px 24px",
    "textAlign": "center",
    "minWidth": "150px",
    "flex": "1",
    "boxShadow": "0 1px 3px rgba(0,0,0,0.1)",
    "border": "1px solid #e8e8e8",
}
CARD_VALUE_STYLE = {"color": "#0f9b8e", "fontSize": "28px", "fontWeight": "bold", "margin": "0"}
CARD_LABEL_STYLE = {"color": "#444", "fontSize": "12px", "margin": "4px 0 0 0"}


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
    parts = pkg.split(".")
    if len(parts) >= 3:
        last = parts[-1]
        if last in ("porsche", "mib4"):
            return parts[-2] if len(parts) > 2 else last
        return last
    return parts[-1] if parts else pkg


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

# ---------------------------------------------------------------------------
# Dash App
# ---------------------------------------------------------------------------

app = Dash(__name__)
app.title = "AAOS Telemetry Dashboard"

# Override Dash slider accent color via index_string
app.index_string = '''<!DOCTYPE html>
<html>
<head>
{%metas%}
<title>{%title%}</title>
{%favicon%}
{%css%}
<style>
.rc-slider-track { background-color: #0f9b8e !important; }
.rc-slider-handle { border-color: #0f9b8e !important; }
.rc-slider-handle:hover, .rc-slider-handle:active { border-color: #0d8a7e !important; box-shadow: 0 0 5px #0f9b8e !important; }
.rc-slider-dot-active { border-color: #0f9b8e !important; }
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
    style={"backgroundColor": "#f5f6fa", "minHeight": "100vh", "padding": "20px 30px",
           "fontFamily": "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif", "color": "#111"},
    children=[
        # Header
        html.Div(
            style={"display": "flex", "alignItems": "center", "justifyContent": "space-between",
                   "marginBottom": "20px"},
            children=[
                html.H1("AAOS Telemetry Dashboard",
                         style={"color": "#0f9b8e", "fontSize": "24px", "margin": 0}),
                html.Span(f"Data: {MIN_DT.strftime('%Y-%m-%d %H:%M')} — {MAX_DT.strftime('%H:%M')}",
                           style={"color": "#888", "fontSize": "13px"}),
            ],
        ),

        # Filters area
        html.Div(
            style={"marginBottom": "24px", "backgroundColor": "#ffffff", "padding": "16px 20px",
                   "borderRadius": "8px", "boxShadow": "0 1px 3px rgba(0,0,0,0.08)"},
            children=[
                # Row 1: Start / End datetime pickers
                html.Div(
                    style={"display": "flex", "gap": "16px", "alignItems": "flex-end",
                           "marginBottom": "12px"},
                    children=[
                        html.Div([
                            html.Label("Start", style={"fontSize": "11px", "color": "#888",
                                                        "display": "block", "marginBottom": "4px"}),
                            dcc.Input(
                                id="start-datetime",
                                type="text",
                                value=MIN_DT.strftime("%Y-%m-%d %H:%M:%S"),
                                style={"fontSize": "13px", "padding": "6px 10px",
                                       "border": "1px solid #ddd", "borderRadius": "4px",
                                       "width": "180px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("End", style={"fontSize": "11px", "color": "#888",
                                                      "display": "block", "marginBottom": "4px"}),
                            dcc.Input(
                                id="end-datetime",
                                type="text",
                                value=MAX_DT.strftime("%Y-%m-%d %H:%M:%S"),
                                style={"fontSize": "13px", "padding": "6px 10px",
                                       "border": "1px solid #ddd", "borderRadius": "4px",
                                       "width": "180px"},
                            ),
                        ]),
                        html.Span(
                            f"Range: {MIN_DT.strftime('%Y-%m-%d %H:%M')} — {MAX_DT.strftime('%Y-%m-%d %H:%M')}",
                            style={"color": "#aaa", "fontSize": "11px", "paddingBottom": "6px"},
                        ),
                    ],
                ),
                # Row 2: Time slider (full width)
                html.Div([
                    dcc.RangeSlider(
                        id="time-slider",
                        min=MIN_TS, max=MAX_TS, value=[MIN_TS, MAX_TS],
                        marks={int(t): {"label": pd.to_datetime(t, unit="ms").strftime("%H:%M"),
                                        "style": {"fontSize": "9px", "color": "#888"}}
                               for t in [MIN_TS + i * (MAX_TS - MIN_TS) / 4 for i in range(5)]},
                        tooltip={"always_visible": False},
                    ),
                ], style={"marginBottom": "12px"}),
                # Row 3: Dropdowns
                html.Div(
                    style={"display": "flex", "gap": "16px", "alignItems": "flex-end", "flexWrap": "wrap"},
                    children=[
                        html.Div([
                            html.Label("Displays", style={"fontSize": "11px", "color": "#888",
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
                            html.Label("Apps", style={"fontSize": "11px", "color": "#888",
                                                       "display": "block", "marginBottom": "4px"}),
                            dcc.Dropdown(
                                id="app-filter",
                                options=[{"label": _short_name(a), "value": a} for a in ALL_APPS],
                                value=[], multi=True, placeholder="All apps",
                                style={"minWidth": "200px"},
                            ),
                        ]),
                        html.Div([
                            html.Label("Trigger", style={"fontSize": "11px", "color": "#888",
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
            colors={"border": "#e8e8e8", "primary": "#0f9b8e", "background": "#ffffff"},
            style={"marginBottom": "20px"},
            children=[
                dcc.Tab(label="Timeline", value="timeline",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="App Usage", value="app_usage",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="Touch", value="touch",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="Performance", value="performance",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="Network", value="network",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="Vehicle & GPS", value="vehicle",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
                dcc.Tab(label="Collector Stats", value="overview",
                        style={"backgroundColor": "#fff", "color": "#888", "padding": "8px 16px", "border": "1px solid #e8e8e8"},
                        selected_style={"backgroundColor": "#0f9b8e", "color": "#fff", "padding": "8px 16px"}),
            ],
        ),

        # Chart content
        html.Div(id="tab-content", style={"minHeight": "600px"}),
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


# Consistent margins for timeline charts so x-axes align
_TIMELINE_MARGIN = dict(l=120, r=40, t=40, b=30)


def _timeline_graph(fig, height=400):
    _apply_theme(fig, height)
    fig.update_layout(margin=_TIMELINE_MARGIN)
    return dcc.Graph(figure=fig, config={"displayModeBar": True, "displaylogo": False})


def _row(*children):
    return html.Div(style={"display": "grid", "gridTemplateColumns": "1fr 1fr", "gap": "20px",
                           "marginBottom": "20px"}, children=list(children))


def _full(*children):
    return html.Div(style={"marginBottom": "20px"}, children=list(children))


# ---------------------------------------------------------------------------
# Callbacks
# ---------------------------------------------------------------------------


@callback(
    Output("time-slider", "value"),
    Output("start-datetime", "value"),
    Output("end-datetime", "value"),
    Input("start-datetime", "n_submit"),
    Input("end-datetime", "n_submit"),
    Input("time-slider", "value"),
    State("start-datetime", "value"),
    State("end-datetime", "value"),
    prevent_initial_call=True,
)
def sync_time_controls(_s_submit, _e_submit, slider_val, start_str, end_str):
    """Keep datetime text inputs and slider in sync."""
    trigger = ctx.triggered_id
    if trigger in ("start-datetime", "end-datetime"):
        # Text input submitted — parse and push to slider
        try:
            start_ts = int(pd.to_datetime(start_str).timestamp() * 1000)
        except Exception:
            start_ts = MIN_TS
        try:
            end_ts = int(pd.to_datetime(end_str).timestamp() * 1000)
        except Exception:
            end_ts = MAX_TS
        start_ts = max(MIN_TS, min(start_ts, MAX_TS))
        end_ts = max(MIN_TS, min(end_ts, MAX_TS))
        s = pd.to_datetime(start_ts, unit="ms").strftime("%Y-%m-%d %H:%M:%S")
        e = pd.to_datetime(end_ts, unit="ms").strftime("%Y-%m-%d %H:%M:%S")
        return [start_ts, end_ts], s, e
    # Slider moved — update text inputs
    s = pd.to_datetime(slider_val[0], unit="ms").strftime("%Y-%m-%d %H:%M:%S")
    e = pd.to_datetime(slider_val[1], unit="ms").strftime("%Y-%m-%d %H:%M:%S")
    return slider_val, s, e


@callback(
    Output("kpi-cards", "children"),
    Output("tab-content", "children"),
    Input("time-slider", "value"),
    Input("display-filter", "value"),
    Input("app-filter", "value"),
    Input("trigger-filter", "value"),
    Input("section-tabs", "value"),
)
def update_dashboard(time_range, displays, apps, triggers, active_tab):
    events, dfs = _filter_events(time_range)
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

    # Touch count
    touch_count = sum(len(dfs.get(a, pd.DataFrame())) for a in ["Touch_Down", "Touch_Swipe"])
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

    # Tab content
    if active_tab == "timeline":
        content = _build_timeline_tab(events, dfs, displays)
    elif active_tab == "overview":
        content = _build_overview_tab(events, dfs)
    elif active_tab == "app_usage":
        content = _build_app_usage_tab(dfs, displays, apps)
    elif active_tab == "touch":
        content = _build_touch_tab(dfs, displays)
    elif active_tab == "performance":
        content = _build_performance_tab(dfs)
    elif active_tab == "network":
        content = _build_network_tab(dfs, apps)
    elif active_tab == "vehicle":
        content = _build_vehicle_tab(dfs)
    else:
        content = html.P("Select a tab")

    return cards, content


# ---------------------------------------------------------------------------
# Tab Builders
# ---------------------------------------------------------------------------


def _build_timeline_tab(events, dfs, displays):
    """Unified stacked timeline: separate figures for app, touches, FPS, memory, network."""
    children = []
    children.append(html.P(
        "Synchronized timeline — zoom any row independently to inspect correlations.",
        style={"color": "#888", "fontSize": "12px", "marginBottom": "8px"},
    ))

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
            if len(df_d) < 2:
                continue
            for i in range(len(df_d) - 1):
                pkg = _short_name(str(df_d.iloc[i]["current_pkg"]))
                start = df_d.iloc[i]["datetime"]
                end = df_d.iloc[i + 1]["datetime"]
                dur_s = (end - start).total_seconds()
                if 0 < dur_s < 3600:
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
            xaxis=dict(type="date"),
            height=80 * n_disp + 120,
            legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
        )
        children.append(_full(_timeline_graph(fig_app, 80 * n_disp + 120)))

    # --- 2) Touch Rate (bars per 5s) ---
    touch_actions = ["Touch_Down", "Touch_Swipe"]
    touch_dfs_list = [dfs.get(a, pd.DataFrame()) for a in touch_actions if not dfs.get(a, pd.DataFrame()).empty]
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
            )
            children.append(_full(_timeline_graph(fig_touch, 250)))

    # --- 3) FPS + Dropped Frames ---
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    if not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "fps" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_fps = make_subplots(specs=[[{"secondary_y": True}]])
            fig_fps.add_trace(go.Scatter(
                x=fps_exp["datetime"], y=fps_exp["fps"], mode="lines",
                name="FPS", line=dict(color="#0f9b8e", width=2),
            ), secondary_y=False)
            fig_fps.add_hline(y=60, line_dash="dash", line_color="green",
                              annotation_text="60fps", secondary_y=False)
            if "dropped" in fps_exp.columns:
                fig_fps.add_trace(go.Bar(
                    x=fps_exp["datetime"], y=fps_exp["dropped"],
                    name="Dropped", marker_color="rgba(231,76,60,0.85)",
                ), secondary_y=True)
                fig_fps.update_yaxes(title_text="Dropped", secondary_y=True)
            fig_fps.update_layout(
                title="FPS & Dropped Frames", height=280,
                legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
            )
            fig_fps.update_yaxes(title_text="FPS", secondary_y=False)
            children.append(_full(_timeline_graph(fig_fps, 280)))

    # --- 4) Available Memory ---
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    if not df_mem.empty:
        mem_exp = expand_samples(df_mem)
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            fig_mem = go.Figure()
            fig_mem.add_trace(go.Scatter(
                x=mem_exp["datetime"], y=mem_exp["availMb"], mode="lines",
                name="Available", line=dict(color="#0f9b8e", width=2),
                fill="tozeroy", fillcolor="rgba(15,155,142,0.1)",
            ))
            # Add total memory as dashed red line
            total_mb = None
            if "totalMem" in df_mem.columns:
                total_vals = df_mem["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_mb = int(total_vals.iloc[0]) / (1024 * 1024)
            if total_mb:
                fig_mem.add_hline(y=total_mb, line_dash="dash", line_color="red",
                                  annotation_text=f"Total: {total_mb:.0f} MB")
                fig_mem.add_trace(go.Scatter(
                    x=[None], y=[None], mode="lines",
                    line=dict(color="red", dash="dash", width=2),
                    name=f"Total ({total_mb:.0f} MB)", showlegend=True,
                ))
            fig_mem.update_layout(
                title="Available Memory (MB)", yaxis_title="MB", height=250,
                legend=dict(orientation="h", y=1.0, yanchor="bottom", x=0, font=dict(size=9)),
            )
            children.append(_full(_timeline_graph(fig_mem, 250)))

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
                )
                children.append(_full(_timeline_graph(fig_net, 300)))

    if not children or len(children) <= 1:
        children.append(html.P("Insufficient data for timeline.", style={"color": "#888"}))

    return html.Div(children)


def _build_overview_tab(events, dfs):
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

    # Combined: Event Rate + Data Volume (toggleable via buttons)
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
            fig_combined.add_trace(go.Scatter(
                x=d["time_bin"], y=d["count"], mode="lines+markers",
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
            fig_combined.add_trace(go.Scatter(
                x=d["time_bin"], y=d["KB"], mode="lines+markers",
                name=coll, visible=False,
            ))
            rate_traces.append(False)
            vol_traces.append(True)

    n_total = len(rate_traces)
    fig_combined.update_layout(
        title=dict(text="Event Rate (per minute)", y=0.95),
        updatemenus=[dict(
            type="buttons", direction="right",
            x=0.5, y=1.12, xanchor="center",
            buttons=[
                dict(label="Event Rate",
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

    children.append(_row(_graph(fig1, 450), _graph(fig_combined, 450)))

    # --- System Impact Section ---
    children.append(html.H3("System Impact",
                             style={"color": "#0f9b8e", "marginTop": "32px", "marginBottom": "8px"}))
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
                mem_exp["time_bin"] = mem_exp["datetime"].dt.floor("1min")
                mem_avg = mem_exp.groupby("time_bin")["availMb"].mean().reset_index()
                merged = rate.merge(mem_avg, on="time_bin", how="inner")
                if not merged.empty:
                    fig_ev_mem = make_subplots(specs=[[{"secondary_y": True}]])
                    fig_ev_mem.add_trace(go.Bar(x=merged["time_bin"], y=merged["events_per_min"],
                                                name="Events/min", marker_color="rgba(15,155,142,0.5)"),
                                         secondary_y=False)
                    fig_ev_mem.add_trace(go.Scatter(x=merged["time_bin"], y=merged["availMb"],
                                                    mode="lines+markers", name="Available Memory (MB)",
                                                    line=dict(color="#e74c3c", width=2)),
                                         secondary_y=True)
                    fig_ev_mem.update_layout(title="Event Throughput vs Available Memory")
                    fig_ev_mem.update_yaxes(title_text="Events/min", secondary_y=False)
                    fig_ev_mem.update_yaxes(title_text="MB", secondary_y=True)

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

        children.append(_row(_graph(fig_ev_mem, 400), _graph(fig_ev_fps, 400)))

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
                    children.append(_full(_graph(fig_ev_cpu, 380)))

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
                    sm_figs.append(_graph(fig_sm, 300))
                if "memMb" in self_exp.columns:
                    self_exp["memMb"] = pd.to_numeric(self_exp["memMb"], errors="coerce")
                    fig_sm_m = px.line(self_exp.dropna(subset=["memMb"]),
                                       x="datetime", y="memMb",
                                       title="DataCollector Own Memory (MB)", markers=True)
                    fig_sm_m.update_traces(line=dict(color="#5b8def"))
                    sm_figs.append(_graph(fig_sm_m, 300))
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


def _build_app_usage_tab(dfs, displays, apps):
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
        if len(df_disp) < 2:
            continue
        y_val = idx
        for i in range(len(df_disp) - 1):
            pkg = str(df_disp.iloc[i]["current_pkg"])
            short = _short_name(pkg)
            start = df_disp.iloc[i]["datetime"]
            end = df_disp.iloc[i + 1]["datetime"]
            dur_s = (end - start).total_seconds()
            if 0 < dur_s < 3600:
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
    children.append(_full(_graph(fig3, 120 * n_disp + 100)))

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


def _build_touch_tab(dfs, displays):
    touch_actions = ["Touch_Down", "Touch_Up", "Touch_Swipe"]
    touch_dfs = [dfs.get(a, pd.DataFrame()) for a in touch_actions if not dfs.get(a, pd.DataFrame()).empty]
    if not touch_dfs:
        return html.P("No touch data available.", style={"color": "#888"})

    children = []
    all_touch = pd.concat(touch_dfs, ignore_index=True)

    # Heatmaps per display
    heatmap_actions = ["Touch_Down", "Touch_Swipe"]
    heatmap_dfs = [dfs.get(a, pd.DataFrame()) for a in heatmap_actions if not dfs.get(a, pd.DataFrame()).empty]
    df_heatmap = pd.concat(heatmap_dfs, ignore_index=True) if heatmap_dfs else pd.DataFrame()

    heatmap_figs = []
    if not df_heatmap.empty and "displayId" in df_heatmap.columns:
        for did in sorted(displays):
            if did in TOUCH_EXCLUDED_DISPLAYS:
                continue
            df_disp = df_heatmap[df_heatmap["displayId"] == did]
            if df_disp.empty or "x" not in df_disp.columns:
                continue
            name = DISPLAY_NAMES.get(did, f"Display {did}")
            res = DISPLAY_RESOLUTIONS.get(did, (1920, 720))
            n = len(df_disp)
            bin_size = 80 if n < 50 else (40 if n < 200 else 20)
            fig = go.Figure()
            fig.add_trace(go.Histogram2d(
                x=df_disp["x"].tolist(), y=df_disp["y"].tolist(),
                nbinsx=max(10, res[0] // bin_size), nbinsy=max(8, res[1] // bin_size),
                colorscale=[[0, "rgba(30,30,50,0)"], [0.15, "#4575b4"], [0.35, "#91bfdb"],
                            [0.5, "#fee090"], [0.7, "#fc8d59"], [1.0, "#d73027"]],
                zsmooth="best",
            ))
            fig.add_shape(type="rect", x0=0, y0=0, x1=res[0], y1=res[1],
                          line=dict(color="#555", width=2))
            fig_h = min(250, int(280 * res[1] / res[0]) + 40)
            fig.update_layout(
                title=f"Touch Heatmap — {name} (n={n})",
                xaxis=dict(range=[0, res[0]], showgrid=False, constrain="domain"),
                yaxis=dict(range=[res[1], 0], showgrid=False, scaleanchor="x", constrain="domain"),
            )
            heatmap_figs.append(_graph(fig, fig_h))

    if heatmap_figs:
        children.append(html.Div(
            style={"display": "grid", "gridTemplateColumns": f"repeat({min(len(heatmap_figs), 3)}, 1fr)",
                   "gap": "8px", "marginBottom": "20px"},
            children=heatmap_figs,
        ))

    # Touch Rate vs Dropped Frames & FPS
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    fig_touch_fps = go.Figure()
    if not all_touch.empty and not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "dropped" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_touch_fps = make_subplots(specs=[[{"secondary_y": True}]])
            # Dropped frames as line (reported every 5s, so continuous)
            fps_exp["time_bin"] = fps_exp["datetime"].dt.floor("5s")
            drops = fps_exp.groupby("time_bin")["dropped"].sum().reset_index()
            fig_touch_fps.add_trace(go.Scatter(
                x=drops["time_bin"], y=drops["dropped"],
                mode="lines", name="Dropped Frames",
                line=dict(color="#e74c3c", width=2)),
                secondary_y=True)
            # Touch rate as bars (per 5 seconds, sparse)
            if "displayId" in all_touch.columns:
                for did in sorted(all_touch["displayId"].dropna().unique()):
                    did_int = int(did)
                    if did_int not in displays:
                        continue
                    df_d = all_touch[all_touch["displayId"] == did_int].copy()
                    df_d["time_bin"] = df_d["datetime"].dt.floor("5s")
                    rate_d = df_d.groupby("time_bin").size().reset_index(name="touches")
                    rate_d["tpm"] = rate_d["touches"] * 12  # per minute from 5s bins
                    fig_touch_fps.add_trace(go.Bar(
                        x=rate_d["time_bin"], y=rate_d["tpm"],
                        name=f"Touches — {DISPLAY_NAMES.get(did_int, '')}",
                        marker_color=DISPLAY_COLORS.get(did_int, "#888"),
                        opacity=0.7), secondary_y=False)
            fig_touch_fps.update_layout(title="Touch Rate vs Dropped Frames", barmode="stack")
            fig_touch_fps.update_yaxes(title_text="Touches/min", secondary_y=False)
            fig_touch_fps.update_yaxes(title_text="Dropped Frames", secondary_y=True)
    children.append(_full(_graph(fig_touch_fps, 400)))

    return html.Div(children)


def _build_performance_tab(dfs):
    children = []

    # Memory
    df_mem = dfs.get("Memory_Usage", pd.DataFrame())
    fig_mem = go.Figure()
    if not df_mem.empty:
        mem_exp = expand_samples(df_mem)
        if "availMb" in mem_exp.columns and "datetime" in mem_exp.columns:
            fig_mem.add_trace(go.Scatter(x=mem_exp["datetime"], y=mem_exp["availMb"],
                                         mode="lines+markers", name="Available MB",
                                         line=dict(color="#0f9b8e")))
            total_mb = None
            if "totalMem" in df_mem.columns:
                total_vals = df_mem["totalMem"].dropna()
                if len(total_vals) > 0:
                    total_mb = int(total_vals.iloc[0]) / (1024 * 1024)
            if total_mb:
                fig_mem.add_hline(y=total_mb, line_dash="dash", line_color="red",
                                  annotation_text=f"Total: {total_mb:.0f} MB")
            fig_mem.update_layout(title="Memory Over Time", yaxis_title="MB")

    # FPS + Dropped Frames combined
    df_fps = dfs.get("Display_FrameRate", pd.DataFrame())
    fig_fps = go.Figure()
    if not df_fps.empty:
        fps_exp = expand_samples(df_fps)
        if "fps" in fps_exp.columns and "datetime" in fps_exp.columns:
            fig_fps = make_subplots(specs=[[{"secondary_y": True}]])
            fig_fps.add_trace(go.Scatter(x=fps_exp["datetime"], y=fps_exp["fps"],
                                          mode="lines+markers", name="FPS",
                                          line=dict(color="#0f9b8e")), secondary_y=False)
            fig_fps.add_hline(y=60, line_dash="dash", line_color="green",
                              annotation_text="60fps", secondary_y=False)
            if "dropped" in fps_exp.columns:
                fig_fps.add_trace(go.Bar(x=fps_exp["datetime"], y=fps_exp["dropped"],
                                          name="Dropped Frames", marker_color="rgba(231,76,60,0.85)",
                                          ), secondary_y=True)
            fig_fps.update_layout(title="Frame Rate & Dropped Frames")
            fig_fps.update_yaxes(title_text="FPS", secondary_y=False)
            fig_fps.update_yaxes(title_text="Dropped", secondary_y=True)

    children.append(_row(_graph(fig_mem, 380), _graph(fig_fps, 380)))

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
            children.append(_row(_graph(fig_cpu, 350), _graph(fig_load, 350)))

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
        children.append(_full(_graph(fig_stor, 350)))

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
            children.append(_full(_graph(fig_batt, 280)))

    return html.Div(children)


def _build_network_tab(dfs, apps):
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

    children.append(_row(_graph(fig_signal, 350), _graph(fig_quality, 350)))

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
        app_totals = defaultdict(lambda: {"rx": 0, "tx": 0})
        for _, row in df_perapp.iterrows():
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
                app_totals[label]["rx"] = max(app_totals[label]["rx"], a.get("rxBytes", 0))
                app_totals[label]["tx"] = max(app_totals[label]["tx"], a.get("txBytes", 0))

        traffic_data = []
        for label, data in app_totals.items():
            rx_mb = data["rx"] / (1024 * 1024)
            tx_mb = data["tx"] / (1024 * 1024)
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

    children.append(_row(_graph(fig_traffic, 350), _graph(fig_perapp, max(350, len(app_totals) * 40))))

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
    children.append(_full(_graph(fig_lifecycle, 180 * 6 + 80)))

    return html.Div(children)


def _build_vehicle_tab(dfs):
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

    children.append(_row(_graph(fig_speed, 350), _graph(fig_gear, 250)))

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

                children.append(_row(_graph(fig_speed_gps, 300), _graph(fig_alt, 300)))

            # Accuracy
            if "accuracyMeters" in loc_exp.columns:
                loc_exp["accuracyMeters"] = pd.to_numeric(loc_exp["accuracyMeters"], errors="coerce")
                fig_acc = px.line(loc_exp.dropna(subset=["accuracyMeters"]),
                                 x="datetime", y="accuracyMeters",
                                 title="GPS Accuracy (m)", markers=True)
                fig_acc.update_traces(line=dict(color="#f39c12"))
                children.append(_full(_graph(fig_acc, 250)))

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
            children.append(_full(_graph(fig_cpu, 350)))

            if "loadAvg1min" in cpu_exp.columns:
                cpu_exp["loadAvg1min"] = pd.to_numeric(cpu_exp["loadAvg1min"], errors="coerce")
                fig_load = px.line(cpu_exp.dropna(subset=["loadAvg1min"]),
                                   x="datetime", y="loadAvg1min",
                                   title="1-min Load Average", markers=True)
                fig_load.update_traces(line=dict(color="#9b59b6"))
                children.append(_full(_graph(fig_load, 250)))

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
                sm_figs.append(_graph(fig_sm_cpu, 300))
            if "memMb" in self_exp.columns:
                self_exp["memMb"] = pd.to_numeric(self_exp["memMb"], errors="coerce")
                fig_sm_mem = px.line(self_exp.dropna(subset=["memMb"]),
                                     x="datetime", y="memMb",
                                     title="DataCollector Memory (MB)", markers=True)
                fig_sm_mem.update_traces(line=dict(color="#0f9b8e"))
                sm_figs.append(_graph(fig_sm_mem, 300))
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
                children.append(_full(_graph(fig_threads, 250)))

    return html.Div(children)


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    print("[Dashboard] Starting at http://127.0.0.1:8050")
    app.run(debug=True, host="127.0.0.1", port=8050)
