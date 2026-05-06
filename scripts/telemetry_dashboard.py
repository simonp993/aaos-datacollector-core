#!/usr/bin/env python3
"""
Live Telemetry Dashboard — pipes adb logcat → SSE → browser.
Zero external dependencies (stdlib only).

Usage:
    python3 scripts/telemetry_dashboard.py [--port 8765] [--serial emulator-5554] [--filter "DataCollector:LogTelemetry"]
"""

import argparse
import json
import queue
import re
import subprocess
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread

DEFAULT_PORT = 8765
DEFAULT_LOGCAT_FILTER = "DataCollector:LogTelemetry"
JSON_PATTERN = re.compile(r'\{.*\}\s*$')
event_queues: list[queue.Queue] = []
RENDERED_HTML = ""

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Telemetry Dashboard</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; color: #222; font-size: 13px; height: 100vh; display: flex; flex-direction: column; }
header { background: #fff; border-bottom: 1px solid #ddd; padding: 10px 16px; display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; flex-shrink: 0; }
.title-block { display: flex; flex-direction: column; gap: 4px; }
h1 { font-size: 15px; font-weight: 600; color: #111; }
.badges { display: flex; gap: 8px; align-items: center; }
.status { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.status.connected { background: #d1fae5; color: #065f46; }
.status.disconnected { background: #fee2e2; color: #991b1b; }
.count { font-size: 11px; color: #888; }
.filters { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; flex: 1; }
.fg { display: flex; flex-direction: column; gap: 3px; }
.fg label { font-size: 10px; color: #888; text-transform: uppercase; letter-spacing: 0.05em; }
.fg input, .fg select { border: 1px solid #ccc; background: #fff; color: #222; padding: 4px 8px; border-radius: 4px; font-size: 12px; height: 28px; }
.fg input:focus, .fg select:focus { outline: none; border-color: #4f46e5; box-shadow: 0 0 0 2px #e0e7ff; }
.fg.wide input { min-width: 260px; font-family: monospace; font-size: 11px; }
.misc { display: flex; align-items: flex-end; gap: 8px; }
.misc label { font-size: 11px; color: #888; display: flex; align-items: center; gap: 4px; height: 28px; }
.btn { background: #fff; border: 1px solid #ccc; color: #333; padding: 4px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; height: 28px; }
.btn:hover { background: #f0f0f0; }
.btn.paused { background: #fef3c7; border-color: #f59e0b; color: #92400e; }
#main { flex: 1; overflow-y: auto; }
table { width: 100%; border-collapse: collapse; }
thead th { background: #f0f0f0; border-bottom: 2px solid #ddd; padding: 7px 12px; text-align: left; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: #555; position: sticky; top: 0; z-index: 1; white-space: nowrap; }
tr.data-row { border-bottom: 1px solid #e8e8e8; cursor: pointer; }
tr.data-row:hover { background: #f9f9f9; }
tr.data-row.expanded { background: #eef2ff; }
td { padding: 6px 12px; vertical-align: middle; }
td.ts { color: #888; font-family: monospace; font-size: 11px; white-space: nowrap; }
td.action { font-weight: 500; color: #1e40af; }
td.trigger span { font-size: 10px; padding: 2px 8px; border-radius: 10px; font-weight: 500; }
.trigger-user { background: #d1fae5; color: #065f46; }
.trigger-heartbeat { background: #fef9c3; color: #854d0e; }
.trigger-system { background: #e0e7ff; color: #3730a3; }
td.collector { color: #555; font-size: 11px; }
td.hint { color: #bbb; font-size: 11px; }
tr.payload-row { display: none; }
tr.payload-row.show { display: table-row; }
tr.payload-row td { background: #fafafa; border-bottom: 2px solid #c7d2fe; padding: 12px 16px; }
pre { font-family: 'SF Mono', Consolas, monospace; font-size: 11px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; color: #333; max-height: 420px; overflow-y: auto; }
</style>
</head>
<body>
<header>
  <div class="title-block">
    <h1>Telemetry Dashboard</h1>
    <div class="badges">
      <span id="status" class="status disconnected">disconnected</span>
      <span class="count" id="count">0 events</span>
    </div>
  </div>
  <div class="filters">
    <div class="fg">
      <label>Action Name</label>
      <input id="f-action" type="text" placeholder="e.g. Audio_Volume">
    </div>
    <div class="fg">
      <label>Trigger</label>
      <select id="f-trigger">
        <option value="">All</option>
        <option value="user">user</option>
        <option value="heartbeat">heartbeat</option>
        <option value="system">system</option>
      </select>
    </div>
    <div class="fg">
      <label>Collector</label>
      <input id="f-collector" type="text" placeholder="e.g. Audio">
    </div>
    <div class="fg wide">
      <label>Logcat filter (restart to change)</label>
      <input type="text" value="{{LOGCAT_FILTER}}" readonly title="Pass --filter on the command line to change this">
    </div>
  </div>
  <div class="misc">
    <label><input type="checkbox" id="autoScroll" checked> Auto-scroll</label>
    <button class="btn" onclick="clearEvents()">Clear</button>
    <button class="btn" id="pauseBtn" onclick="togglePause()">Pause</button>
  </div>
</header>
<div id="main">
  <table>
    <thead><tr>
      <th>Timestamp</th><th>Action Name</th><th>Trigger</th><th>Collector</th><th>Payload</th>
    </tr></thead>
    <tbody id="tbody"></tbody>
  </table>
</div>
<script>
const tbody = document.getElementById('tbody');
const statusEl = document.getElementById('status');
const countEl = document.getElementById('count');
const autoScrollEl = document.getElementById('autoScroll');
const pauseBtn = document.getElementById('pauseBtn');
let paused = false, eventCount = 0, uid = 0;
let allEvents = [];
function getFilters() {
  return {
    action: document.getElementById('f-action').value.toLowerCase(),
    trigger: document.getElementById('f-trigger').value,
    collector: document.getElementById('f-collector').value.toLowerCase(),
  };
}
function matches(data, f) {
  const action = (data.payload && data.payload.actionName || '').toLowerCase();
  const trigger = data.payload && data.payload.trigger || '';
  const collector = (data.signalId || '').split('.').pop().toLowerCase();
  if (f.action && action.indexOf(f.action) === -1) return false;
  if (f.trigger && trigger !== f.trigger) return false;
  if (f.collector && collector.indexOf(f.collector) === -1) return false;
  return true;
}
function renderEvent(data) {
  const f = getFilters();
  if (!matches(data, f)) return;
  const action = data.payload && data.payload.actionName || 'unknown';
  const trigger = data.payload && data.payload.trigger || '';
  const collector = (data.signalId || '').split('.').pop();
  const ts = data.timestamp ? new Date(data.timestamp).toLocaleTimeString('en-GB', {hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit',fractionalSecondDigits:3}) : '';
  const id = ++uid;
  const badge = trigger ? '<span class="trigger-' + trigger + '">' + trigger + '</span>' : '';
  const row = document.createElement('tr');
  row.className = 'data-row';
  row.onclick = function() { row.classList.toggle('expanded'); document.getElementById('pr' + id).classList.toggle('show'); };
  row.innerHTML = '<td class="ts">' + ts + '</td><td class="action">' + action + '</td><td class="trigger">' + badge + '</td><td class="collector">' + collector + '</td><td class="hint">&#9654; expand</td>';
  const prow = document.createElement('tr');
  prow.className = 'payload-row';
  prow.id = 'pr' + id;
  prow.innerHTML = '<td colspan="5"><pre>' + JSON.stringify(data, null, 2) + '</pre></td>';
  tbody.appendChild(row);
  tbody.appendChild(prow);
  if (autoScrollEl.checked) row.scrollIntoView({block:'end'});
}
function rebuildTable() { tbody.innerHTML = ''; allEvents.forEach(renderEvent); }
function clearEvents() { tbody.innerHTML = ''; allEvents = []; eventCount = 0; countEl.textContent = '0 events'; }
function togglePause() { paused = !paused; pauseBtn.textContent = paused ? 'Resume' : 'Pause'; pauseBtn.classList.toggle('paused', paused); }
['f-action', 'f-trigger', 'f-collector'].forEach(function(id) { document.getElementById(id).addEventListener('input', rebuildTable); });
function connect() {
  var es = new EventSource('/events');
  es.onopen = function() { statusEl.textContent = 'connected'; statusEl.className = 'status connected'; };
  es.onerror = function() { statusEl.textContent = 'disconnected'; statusEl.className = 'status disconnected'; };
  es.onmessage = function(e) {
    if (paused) return;
    try { var d = JSON.parse(e.data); allEvents.push(d); eventCount++; countEl.textContent = eventCount + ' events'; renderEvent(d); } catch(err) {}
  };
}
connect();
</script>
</body>
</html>"""


class DashboardHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/events':
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            q = queue.Queue()
            event_queues.append(q)
            try:
                while True:
                    data = q.get()
                    self.wfile.write(f"data: {data}\n\n".encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                event_queues.remove(q)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(RENDERED_HTML.encode())

    def log_message(self, format, *args):
        pass


def broadcast(message: str):
    for q in event_queues[:]:
        try:
            q.put_nowait(message)
        except queue.Full:
            pass


def logcat_reader(serial: str | None, logcat_filter: str):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-v", "time"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    print(f"[logcat] filter='{logcat_filter}'  device={'device ' + serial if serial else 'default'}...")
    for line in iter(proc.stdout.readline, b''):
        decoded = line.decode("utf-8", errors="replace").strip()
        if logcat_filter and logcat_filter not in decoded:
            continue
        match = JSON_PATTERN.search(decoded)
        if match:
            try:
                broadcast(json.dumps(json.loads(match.group(0))))
            except json.JSONDecodeError:
                pass


def main():
    global RENDERED_HTML
    parser = argparse.ArgumentParser(description="Live Telemetry Dashboard")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--serial", "-s", type=str, default=None, help="adb device serial")
    parser.add_argument("--filter", "-f", type=str, default=DEFAULT_LOGCAT_FILTER, help="Logcat line filter string")
    args = parser.parse_args()

    RENDERED_HTML = HTML_TEMPLATE.replace("{{LOGCAT_FILTER}}", args.filter)

    Thread(target=logcat_reader, args=(args.serial, args.filter), daemon=True).start()
    print(f"[dashboard] http://localhost:{args.port}")
    print(f"[dashboard] Press Ctrl+C to stop\n")
    webbrowser.open(f"http://localhost:{args.port}")
    HTTPServer(("0.0.0.0", args.port), DashboardHandler).serve_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[dashboard] Stopped.")
