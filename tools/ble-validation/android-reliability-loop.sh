#!/usr/bin/env bash
# Headless connect-establishment reliability runner (Android central), for the BlueFalcon-vs-raw
# comparison. Method, arms and gates: tools/ble-validation/README.md.
#
# Runs ONE batch of the on-device ConnectReliabilityE2ETest via a single `am instrument` call (no
# per-interaction adb, no UI automation), captures the epoch-stamped RELIABILITY logcat lines, and
# prints the per-attempt failure rate. Pass test args through as KEY=VALUE (e.g. arm=raw count=50).
#
#   ./android-reliability-loop.sh <label> [arm=bluefalcon|raw|leak] [count=30] [autoConnect=false] \
#                                 [connectTimeoutMs=5000] [settleMs=750] [name=..] [address=..] \
#                                 [mtu=false] [probeCount=8] [probeGapMs=2500]
#
# Env: ADB (adb path), PKG (app id), OUT (output dir).
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="${PKG:-com.snapshotpb.configuration}"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
CLASS="$PKG.ConnectReliabilityE2ETest"
OUT="${OUT:-/tmp/ble-validation/android-reliability}"

label="${1:-run}"; shift || true
mkdir -p "$OUT"
stamp="$(date +%Y%m%d-%H%M%S)"
base="$OUT/${label}-${stamp}"

# Build the -e arg list from KEY=VALUE pairs.
eargs=(-e class "$CLASS")
for kv in "$@"; do
  k="${kv%%=*}"; v="${kv#*=}"
  eargs+=(-e "$k" "$v")
done

echo "== $label :: am instrument ${eargs[*]} =="
"$ADB" logcat -c || true
# Capture RELIABILITY (results) + BlueFalcon (engine internals) + the Android system GATT tags
# (status codes + clientIf numbers — the clientIf-leak tell) with epoch timestamps.
"$ADB" logcat -v epoch RELIABILITY:V BlueFalcon:V BluetoothGatt:V BtGatt:V bt_btif:V "*:S" > "$base.logcat" 2>&1 &
logpid=$!
trap 'kill "$logpid" 2>/dev/null || true' EXIT

"$ADB" shell am instrument -w -r "${eargs[@]}" "$RUNNER" | tee "$base.instrument" || true
sleep 1
kill "$logpid" 2>/dev/null || true
trap - EXIT

echo "== results ($base.logcat) =="
grep -E "RELIABILITY: (START|arm=|SUMMARY)" "$base.logcat" | sed -E 's/^ *[0-9.]+ +[0-9]+ +[0-9]+ //' || true
echo "== board-side reasons seen by raw arm (if any) =="
grep -oE "detail=[a-z0-9-]+|connStatus=-?[0-9]+" "$base.logcat" | sort | uniq -c | sort -rn || true
echo "logs: $base.logcat / $base.instrument"
