#!/usr/bin/env bash
#
# BLE connect-reliability batch for cycling firmware builds against the real board.
# Per iteration: restart the board -> run the Android E2E connect test -> classify
# the BT-stack result as CONNECT_OK / CONNECT_FAIL -> tally. Aborts early once the
# build is clearly no better than baseline (MAX_FAILS).
#
# Connect-reliability is what we measure: a run is CONNECT_FAIL only if the link
# never establishes (GATT 133 / reason 0x3e, or "Finish Configuration" never
# appears). The downstream uiautomator/Compose text-entry flake counts as OK.
#
# Env (defaults):
#   PORT=/dev/cu.usbserial-1120          board UART
#   FW_DIR=$HOME/workspace/Firmware      west workspace (for reflash mode)
#   ADB=$HOME/Library/Android/sdk/platform-tools/adb
#   PKG=com.snapshotpb.configuration
#   N=20                                 iterations
#   MAX_FAILS=3                          abort after this many CONNECT_FAILs (screen)
#   RESET_MODE=serial                    serial (fast, ~2s) | reflash (clean NVS, ~14s)
#   BOOT_WAIT=8                          seconds to let the board boot+advertise
#   OUT=/tmp/ble-validation/run-<ts>     output dir (summary + per-run logs)
#
# Example screen:   N=20 RESET_MODE=serial ./reliability-batch.sh
# Example confirm:  N=50 MAX_FAILS=99 ./reliability-batch.sh
set -u
PORT="${PORT:-/dev/cu.usbserial-1120}"
FW_DIR="${FW_DIR:-$HOME/workspace/Firmware}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PY="${PY:-$FW_DIR/zephyr-env/bin/python}"
PKG="${PKG:-com.snapshotpb.configuration}"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
N="${N:-20}"
MAX_FAILS="${MAX_FAILS:-3}"
RESET_MODE="${RESET_MODE:-serial}"
BOOT_WAIT="${BOOT_WAIT:-8}"
OUT="${OUT:-/tmp/ble-validation/run-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"; : > "$SUMMARY"

log() { echo "$*" | tee -a "$SUMMARY"; }

reset_serial() {
  "$PY" - "$PORT" <<'PYEOF'
import serial, sys, time
s = serial.Serial(sys.argv[1], 115200, timeout=0.2)
s.setDTR(False); s.setRTS(True); time.sleep(0.2); s.setRTS(False)
s.close()
PYEOF
}
reflash() {
  ( cd "$FW_DIR" && source zephyr-env/bin/activate && west flash --esp-device "$PORT" ) > "$OUT/flash-$1.log" 2>&1
}

log "=== reliability-batch  N=$N  RESET_MODE=$RESET_MODE  $(date) ==="
fails=0; oks=0
for i in $(seq 1 "$N"); do
  if [ "$RESET_MODE" = reflash ]; then
    reflash "$i" || { log "RUN $i: FLASH_FAILED (see flash-$i.log)"; continue; }
  else
    reset_serial
  fi
  sleep "$BOOT_WAIT"

  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  "$ADB" logcat -c >/dev/null 2>&1
  "$ADB" shell am instrument -w -r -e class "$PKG.ConnectivityE2ETest" "$RUNNER" > "$OUT/test-$i.log" 2>&1
  "$ADB" logcat -d -v time > "$OUT/logcat-$i.log" 2>&1

  if grep -qE 'GATT_ERROR\(133\)|reason=0x003e|bta_gattc_open_fail|Finish Configuration did not appear' \
        "$OUT/logcat-$i.log" "$OUT/test-$i.log" 2>/dev/null; then
    fails=$((fails+1)); log "RUN $i: CONNECT_FAIL    (running: $oks ok / $fails fail)"
  else
    oks=$((oks+1));     log "RUN $i: CONNECT_OK      (running: $oks ok / $fails fail)"
  fi

  if [ "$fails" -ge "$MAX_FAILS" ]; then
    log "=== ABORT: $fails failures reached MAX_FAILS=$MAX_FAILS — build no better than baseline ==="
    break
  fi
  sleep 1
done
total=$((oks+fails))
log "=== DONE: $oks OK / $fails FAIL of $total  ($(awk "BEGIN{if($total)printf \"%.0f\", 100*$fails/$total; else print 0}")% fail)  $(date) ==="
