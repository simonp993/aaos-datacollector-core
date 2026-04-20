#!/usr/bin/env bash
# Captures a screenshot and UI hierarchy dump from a connected Android emulator.
# Usage: ./scripts/capture_screen.sh [output_dir]
set -euo pipefail

OUTPUT_DIR="${1:-.}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
SCREENSHOT_FILE="$OUTPUT_DIR/screenshot_$TIMESTAMP.png"
HIERARCHY_FILE="$OUTPUT_DIR/hierarchy_$TIMESTAMP.xml"

# Verify ADB is available
if ! command -v adb &>/dev/null; then
  echo "Error: adb not found in PATH" >&2
  exit 1
fi

# Verify a device is connected
DEVICE_COUNT="$(adb devices | grep -c 'device$' || true)"
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "Error: no connected devices found" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# Capture screenshot
echo "Capturing screenshot..."
adb exec-out screencap -p > "$SCREENSHOT_FILE"
echo "Screenshot saved: $SCREENSHOT_FILE"

# Capture UI hierarchy
echo "Capturing UI hierarchy..."
adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to: \/dev\/tty//' > "$HIERARCHY_FILE"
echo "Hierarchy saved: $HIERARCHY_FILE"

echo "Done."
