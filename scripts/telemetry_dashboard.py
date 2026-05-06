#!/usr/bin/env python3
"""
Live Telemetry Dashboard — pipes adb logcat → SSE → browser.
Zero external dependencies (stdlib only).

Usage:
    python3 scripts/telemetry_dashboard.py [--port 8765] [--serial emulator-5554]

Opens a browser at http://localhost:8765 showing live telemetry events.
"""

import argparse
import json
import queue
import re
import subprocess
import sys
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread

# --- Configuration ---
DEFAULT_PORT = 8765
LOGCAT_FILTER = "DataCollector:LogTelemetry"
JSON_PATTERN = re.compile(r'\{.*\}\s*$')

# --- Event queue for SSE clients ---
event_queues: list[queue.Queue] = []

HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Telemetry Dashboard</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'SF Mono', monospace; background: #1a1a2e; color: #e0e0e0; padding: 12px; }
h1 { font-size: 16px; color: #7fdbca; margin-bottom: 8px; display: flex; align-items: center; gap: 8px; }
.status { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.status.connected { background: #1b4332; color: #95d5b2; }
.status.disconnected { background: #3d0000; color: #ff6b6b; }
#controls { margin-bottom: 10px; display: flex; gap: 8px; align-items: center; }
#controls input[type=text] { background: #16213e; border: 1px solid #334; color: #e0e0e0; padding: 4px 8px; border-radius: 4px; font-size: 12px; width: 200px; }
#controls button { background: #16213e; border: 1px solid #334; color: #7fdbca; padding: 4px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; }
#controls button:hover { background: #1a3a5c; }
#controls label { font-size: 11px; color: #888; }
#events { display: flex; flex-direction: column; gap: 2px; max-height: calc(100vh - 100px); overflow-y: auto; }
.event { background: #16213e; border: 1px solid #222; border-radius: 4px; padding: 6px 10px; cursor: pointer; transition: background 0.1s; }
.event:hover { background: #1a3a5c; }
.event-header { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.event-time { color: #888; min-width: 85px; }
.event-action { color: #ffd93d; font-weight: 600; flex: 1; }
.event-trigger { font-size: 10px; padding: 1px 6px; border-radius: 8px; }
.trigger-user { background: #1b4332; color: #95d5b2; }
.trigger-heartbeat { background: #2d2d00; color: #ffd93d; }
.trigger-system { background: #1a1a4e; color: #a0a0ff; }
.event-collector { color: #666; font-size: 10px; }
.event-payload { display: none; margin-top: 6px; padding: 8px; background: #0f0f23; border-radius: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; color: #c9d1d9; max-height: 400px; overflow-y: auto; }
.event.expanded .event-payload { display: block; }
.count { color: #666; font-size: 11px; }
</style>
</head>
<body>
<h1>Telemetry Dashboard <span id="status" class="status disconnected">disconnected</span> <span class="count" id="count">0 events</span></h1>
<div id="controls">
  <input id="filter" type="text" placeholder="Filter by action or collector...">
  <label><input type="checkbox" id="autoScroll" checked> Auto-scroll</label>
  <button onclick="clearEvents()">Clear</button>
  <button id="pauseBtn" onclick="togglePause()">Pause</button>
</div>
<div id="events"></div>
<script>
const eventsEl = document.getElementById('events');
const statusEl = document.getElementById('status');
const countEl = document.getElementById('count');
const filterEl = document.getElementById('filter');
const autoScrollEl = document.getElementById('autoScroll');
const pauseBtn = document.getElementById('pauseBtn');
let paused = false;
let eventCount = 0;
let allEvents = [];

function connect() {
  const es = new EventSource('/events');
  es.onopen = () => { statusEl.textContent = 'connected'; statusEl.className = 'status connected'; };
  es.onerror = () => { statusEl.textContent = 'disconnected'; statusEl.className = 'status disconnected'; };
  es.onmessage = (e) => {
    if (paused) return;
    try {
      const data = JSON.parse(e.data);
      allEvents.push(data);
      eventCount++;
      countEl.textContent = eventCount + ' events';
      renderEvent(data);
    } catch(err) {}
  };
}

function renderEvent(data) {
  const filter = filterEl.value.toLowerCase();
  const action = data.payload?.actionName || 'unknown';
  const trigger = data.payload?.trigger || '';
  const collector = (data.signalId || '').split('.').pop();
  if (filter && !action.toLowerCase().includes(filter) && !collector.toLowerCase().includes(filter) && !trigger.includes(filter)) return;

  const ts = data.timestamp ? new Date(data.timestamp).toLocaleTimeString('en-GB', {hour12: false, hour:'2-digit', minute:'2-digit', second:'2-digit', fractionalSecondDigits: 3}) : '';
  const div = document.createElement('div');
  div.className = 'event';
  div.onclick = () => div.classList.toggle('expanded');

  const triggerClass = trigger ? 'trigger-' + trigger : '';
  const triggerBadge = trigger ? `<span class="event-trigger ${triggerClass}">${trigger}</span>` : '';

  div.innerHTML = `
    <div class="event-header">
      <span class="event-time">${ts}</span>
      <span class="event-action">${action}</span>
      ${triggerBadge}
      <span class="event-collector">${collector}</span>
    </div>
    <div class="event-payload">${JSON.stringify(data, null, 2)}</div>
  `;
  eventsEl.appendChild(div);
  if (autoScrollEl.checked) div.scrollIntoView({behavior: 'smooth', block: 'end'});
}

function clearEvents() { eventsEl.innerHTML = ''; allEvents = []; eventCount = 0; countEl.textContent = '0 events'; }
function togglePause() { paused = !paused; pauseBtn.textContent = paused ? 'Resume' : 'Pause'; }

filterEl.addEventListener('input', () => {
  eventsEl.innerHTML = '';
  allEvents.forEach(renderEvent);
});

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
            self.wfile.write(HTML_PAGE.encode())

    def log_message(self, format, *args):
        pass


def broadcast(message: str):
    for q in event_queues[:]:
        try:
            q.put_nowait(message)
        except queue.Full:
            pass


def logcat_reader(serial: str | None):
    """Run adb logcat and broadcast matching JSON lines."""
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += ["logcat", "-v", "time"]

    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    print(f"[logcat] Streaming from {'device ' + serial if serial else 'default device'}...")

    for line in iter(proc.stdout.readline, b''):
        decoded = line.decode("utf-8", errors="replace").strip()
        if LOGCAT_FILTER not in decoded:
            continue
        match = JSON_PATTERN.search(decoded)
        if match:
            raw_json = match.group(0)
            try:
                parsed = json.loads(raw_json)
                broadcast(json.dumps(parsed))
            except json.JSONDecodeError:
                pass


def main():
    parser = argparse.ArgumentParser(description="Live Telemetry Dashboard")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="HTTP/SSE port")
    parser.add_argument("--serial", "-s", type=str, default=None, help="adb device serial")
    args = parser.parse_args()

    # Start logcat reader in background
    logcat_thread = Thread(target=logcat_reader, args=(args.serial,), daemon=True)
    logcat_thread.start()

    print(f"[dashboard] http://localhost:{args.port}")
    print(f"[dashboard] Press Ctrl+C to stop\n")
    webbrowser.open(f"http://localhost:{args.port}")

    server = HTTPServer(("0.0.0.0", args.port), DashboardHandler)
    server.serve_forever()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[dashboard] Stopped.")
