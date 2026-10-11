#!/bin/bash
# Runs one iOS hardware UI test N times against the board, on a Mac with the iPhone connected.
#
#   UDID=<device-udid> tools/ble-validation/ios-hardware-loop.sh <label> <count> <test-name>
#
# Each iteration starts a new UART capture (a board reset, then `ble on`) and runs
# `xcodebuild test-without-building` for the test. Build the tests one time first:
#   xcodebuild build-for-testing -project iosApp/iosApp.xcodeproj -scheme iosAppUITests \
#     -destination "platform=iOS,id=$UDID" -allowProvisioningUpdates
#
# Env:
#   UDID        the iPhone (xcrun devicectl list devices). Required.
#   PORT        board UART (default /dev/cu.usbserial-1110).
#   PY          Python with pyserial (default ~/workspace/Firmware/zephyr-env/bin/python3).
#   OUT         output directory (default /tmp/ble-validation/ios).
#   FRESH=1     uninstall the app first: no saved board, and the Bluetooth prompt shows again.
#   RESET=1     when the test prints ===RESET_BOARD_NOW===, start a new capture, which resets the
#               board and sends `ble on`. The link-loss, write and reconnect tests use this.
#   BOARD_NAME  passed to the test as TEST_RUNNER_BOARD_NAME.
#   RECONNECT_DELAY_SEC  passed to testHardware_reconnectToBoard (the reset window).
#
# Writes <OUT>/<label>-<i>.xcode.log and .board-uart.log, and one line per run to <OUT>/<label>.summary.
set -u
L="$1"; N="$2"; T="$3"
: "${UDID:?set UDID to the iPhone UDID}"
PORT="${PORT:-/dev/cu.usbserial-1110}"
PY="${PY:-$HOME/workspace/Firmware/zephyr-env/bin/python3}"
OUT="${OUT:-/tmp/ble-validation/ios}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
mkdir -p "$OUT"
: > "$OUT/$L.summary"

CAP=""
start_capture() { # <run> <how many BLE_ON_OK lines to wait for>
  [ -n "$CAP" ] && kill "$CAP" 2>/dev/null && wait "$CAP" 2>/dev/null
  "$PY" "$HERE/uart-capture.py" "$PORT" 115200 --ble-on >> "$OUT/$1.board-uart.log" 2>> "$OUT/$1.capture.err" &
  CAP=$!
  for _ in $(seq 1 40); do
    [ "$(grep -c BLE_ON_OK "$OUT/$1.capture.err")" -ge "$2" ] && return
    sleep 0.5
  done
  echo "  the board did not report advertising" >> "$OUT/$L.summary"
}

cd "$REPO/iosApp" || exit 2
for i in $(seq 1 "$N"); do
  R="$L-$i"
  : > "$OUT/$R.board-uart.log"; : > "$OUT/$R.capture.err"
  if [ "${FRESH:-0}" = 1 ]; then
    xcrun devicectl device uninstall app --device "$UDID" com.snapshotpb.configuration.SnapshotPB > /dev/null 2>&1
  fi
  start_capture "$R" 1
  START=$(date +%s)
  TEST_RUNNER_RUN_HARDWARE_TESTS=1 \
  TEST_RUNNER_BOARD_NAME="${BOARD_NAME:-}" \
  TEST_RUNNER_RECONNECT_DELAY_SEC="${RECONNECT_DELAY_SEC:-0}" \
    xcodebuild test-without-building -project iosApp.xcodeproj -scheme iosAppUITests \
    -destination "platform=iOS,id=$UDID" \
    -only-testing:"iosAppUITests/SnapshotPBUITests/$T" > "$OUT/$R.xcode.log" 2>&1 &
  XPID=$!
  RESET_DONE=0
  while kill -0 "$XPID" 2>/dev/null; do
    if [ "${RESET:-0}" = 1 ] && [ $RESET_DONE = 0 ] && grep -q "===RESET_BOARD_NOW===" "$OUT/$R.xcode.log"; then
      start_capture "$R" 2
      RESET_DONE=1
    fi
    sleep 0.5
  done
  wait "$XPID"; RC=$?
  kill "$CAP" 2>/dev/null; wait "$CAP" 2>/dev/null; CAP=""
  MARKS=$(grep -a -o -E 'connProgress:[^ "]*|===(RECONNECT PROGRESS|WRITE VALUE|LINK LOSS DETECTED|WRITE [A-Z ]+ OK|PHOTO FROM [A-Z]+ SAVED)===[^"]*' \
    "$OUT/$R.xcode.log" | sort -u | tr '\n' ' ' | cut -c1-200)
  echo "iter=$i rc=$RC secs=$(( $(date +%s) - START )) reset=$RESET_DONE $MARKS" | tee -a "$OUT/$L.summary"
done
echo "DONE $(grep -c ' rc=0 ' "$OUT/$L.summary")/$N passed" | tee -a "$OUT/$L.summary"
