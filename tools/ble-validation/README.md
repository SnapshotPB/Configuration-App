# BLE validation harness

These tools measure how reliably a central connects to the Snapshot PB board over BLE.
This file is the only how-to for the harness. It holds the method, the pass gates, the commands and the findings format.

The default paths in the scripts are macOS defaults: the board UART is `/dev/cu.usbserial-*` (for example
`/dev/cu.usbserial-1120`) and adb is `~/Library/Android/sdk/platform-tools/adb`. On Linux, the UART is usually `/dev/ttyUSB0`.
On Windows, it is a COM port (for example `COM3`). Set `PORT`, `ADB` or the port argument for your host.

## Comparison

The harness compares each production engine with a raw central. The raw central holds its handle and closes it after each attempt, so it cannot leak.
If the production arm fails and the raw arm does not, the engine is at fault. If both arms fail, examine the board or the RF.

## Failure modes

**Mode A: orphan-connect wedge (engine fault).** The central does not cancel or close a connect that is slow or that fails.
The orphan keeps the one board connection slot busy. After some orphans, the board is not connectable.
To test for it, run each arm in one batch with no board reset between iterations. Reset the board only between arms.
A Mode A wedge shows all four of these signs:

1. The first K iterations are clean. From iteration K, every iteration fails.
2. From iteration K, the board logs no new `Connected`.
3. A Bluetooth off/on cycle on the central does not recover the board.
4. A board power cycle or `reset-board.py` recovers the board.

**Mode B: per-attempt establishment failure.** One connect attempt fails at link establishment, and the board does not log `Connected`.
The central sees HCI reason `0x3e`, Android `GATT_ERROR(133)`, or raw GATT status `62` (the raw arm writes `detail=estab-fail-0x3e`).
The usual cause is the RF margin. A Mode B failure is isolated. The iterations before and after it can be clean.

**CONNECT_OK** has one definition on all platforms: the attempt found the OTS service `0x1825`
(`00001825-0000-1000-8000-00805f9b34fb`) in the time limit of the attempt. All other results are FAIL.

Measure two rates:

- **Per-attempt rate**: fails/N with one connect attempt per iteration and no app retry. A fix must change this rate.
- **User-visible rate**: fails/N through the full retry path (`CONNECT_ATTEMPTS=3`). Mode B failures occur in groups in one board session.
  Thus you must measure this rate. Do not calculate it from the per-attempt rate.

### Classification: central result and board `Connected`

Each iteration writes one line: `RELIABILITY arm=.. iter=K result=OK|FAIL detail=.. ms=M t=<epochMs>`.
`correlate.py` finds whether the board logged `Connected` in the window of that iteration.

| Central result | Board `Connected` in window | Classification |
|---|---|---|
| OK | yes | Clean success. |
| FAIL | no | The link did not complete. This is Mode B if it is isolated. It is Mode A if it is part of a cliff from iteration K that only a board reset clears. |
| FAIL | yes | The link completed, but the central did not find OTS. This is a software fault (for example, stale services), not Mode B. |

Use the time series to select Mode A or Mode B, not one line. The FAIL-with-`Connected` class and the HCI reason
need the diagnostic firmware (`Firmware/app/debug-ble.conf`), which sends the logs to the UART. The shipping firmware
shows only the boot banner on the UART. Thus `correlate.py` can see a reset, but not the reason for each attempt.

## Method

### Single-UART rule

`uart-capture.py`, `reset-board.py` and `serial-monitor.py` each reset the board when they open the port.
Only one process can hold the port. If a script opens the port during a batch, it resets the board and the Mode A run is not valid.

- For a batch, `uart-capture.py` holds the port for the full batch. The reset when it opens the port is the clean start.
  Do not run `reset-board.py` during a capture.
- Between arms or batches, stop the capture, run `reset-board.py`, then start a new capture.
- Use `serial-monitor.py` only for a manual boot check. Do not run it at the same time as a capture.

### Capture, reset, correlate

```bash
# (a) Clean start. The capture holds the port for the full batch.
python3 tools/ble-validation/uart-capture.py <port> 115200 > <label>.board-uart.log &
# (b) Run the central batch. Write its stdout or logcat to <label>.central.log.
# (c) Stop the capture, then correlate:
python3 tools/ble-validation/correlate.py <label>.central.log <label>.board-uart.log
# Between arms: stop the capture, run reset-board.py <port>, then start a new capture.
```

To find the HCI reason of a failed iteration, search `<label>.board-uart.log` for
`bt_hci_le_enh_conn_complete: status` in the window `[T-M-400, T+400]` ms of that iteration.

### RF geometry

At point-blank range (RSSI approximately -35), neither mode occurs. Move the board away from the central (5 m to 8 m, or through one interior wall),
or add attenuation, until the control arm shows 10 % to 30 % per-attempt failures. Then run the production arm at the same geometry.
Record the RSSI for each batch.

### Gates

The baseline (the raw control, or an engine that leaks connect handles) must show a failure rate that is more than zero in the same geometry.
If the baseline shows 0/N, the batch measured nothing.

Mode B does not depend on the central. Thus at the marginal geometry, a correct production engine also fails on some attempts,
at approximately the rate of the raw control. The gate does not ask for 0 fails at this geometry.
It compares each production rate with the raw control rate at the same geometry (refer to [Combine](#combine)).

| Stage | Android and desktop | iOS (smaller N, slow runner) | Result |
|---|---|---|---|
| Screen | N=20 | N=20 | A rate much higher than the control: reject. |
| Confirm | N=50 | N=20 | A rate much higher than the control: reject. |
| Sign-off | N=100 or more | N=20 or more | The rate is not higher than the control: accept the engine or build. |

A per-attempt rate that is higher than the control shows that the engine adds failures. Mode A is the known cause.
The no-reset A/B loop and the `A-wedge` measurement find Mode A directly.

Each platform must also run a sensitivity arm, which proves that the harness can see a failure. Without this proof, a clean result proves nothing.
On firmware that wedges, the leak arm causes a board wedge, and the harness must see it (`wedgeObserved: true`).
The current firmware does not wedge, so use the radio-off arm: the harness finds the board, `ble off` on the UART console stops the radio, and each of N >= 10 connects must fail.
A harness that reports an OK for a board with no radio (for example from stale services on a reused handle) cannot see a failure.

### Run labels

Give each batch one label. Use it as the first argument of the loop script and as the name of the log and summary files.

```
<platform>-<engine>-<arm>-<firmware>-<rf>-N<count>
```

- platform: `android` | `ios` | `desktopMac` | `desktopWin` | `desktopLinux`
- engine: `androidEngine` | `iosEngine` | `macosJvm` | `windows` | `rpi`
- arm: `prod` | `raw` | `leak`. Android uses `arm=bluefalcon|raw|leak`. Desktop uses `-Parm=bluefalcon|leak`, and Bleak is the raw arm.
- firmware: `diag` | `ship`
- rf: `marginal` | `pointblank`

Example: `android-androidEngine-prod-diag-marginal-N100`. Give the UART log the same name with the `.board-uart.log` suffix.
For each batch, record the host, the firmware build and commit, the RSSI, and the `RELIABILITY START` and `SUMMARY` lines.

## Scripts

| Script | Use |
|---|---|
| `reset-board.py [port] [baud]` | Resets the ESP32-C3 and waits for the advertise banner. If the board does not advertise after 3 s, it sends `ble on` (see below). Exit 0: the board advertises. Exit 2: time-out. Then it closes the port. |
| `uart-capture.py [port] [baud] [--ble-on] > board-uart.log` | Resets the board one time, then writes each UART line with a 13-digit epoch-ms prefix. It holds the port for a full batch. With `--ble-on`, it sends `ble on` 3 s after the reset and writes `BLE_ON_OK` to stderr when the board advertises. Stop it with Ctrl-C. |
| `ios-hardware-loop.sh <label> <count> <test>` | Runs one iOS hardware UI test N times on a Mac. Each run starts a new capture with `--ble-on`. Refer to [iOS](#ios). |
| `serial-monitor.py [port] [baud]` | Resets the board and shows the UART lines with an epoch-seconds prefix. Use it for a manual boot check. |
| `correlate.py <central.log> <board-uart.log>` | Aligns each `arm=.. iter=K result=.. ms=M t=T` line with the board events in `[T-M-400, T+400]` ms. It counts OK with `Connected`, FAIL with `Connected`, and FAIL with no `Connected`. |
| `android-reliability-loop.sh <label> [KEY=VALUE ...]` | Runs one batch of `ConnectReliabilityE2ETest` with one `am instrument` call. Each `KEY=VALUE` becomes `-e KEY VALUE`. It writes `<label>-<ts>.logcat` and `<label>-<ts>.instrument` to `$OUT` (default `/tmp/ble-validation/android-reliability`). Env: `ADB`, `PKG`, `OUT`. |
| `reliability-batch.sh` | Loops the full UI test `ConnectivityE2ETest` (the user-visible path). Each iteration resets the board, runs the test and classifies the result. The env variables are in the table below. |
| `board-console.py <port> "<command>" [baud]` | Sends one command to the board shell and prints the answer for 2 s. It does not reset the board. The desktop `radio-off` arm uses it for `ble off` and `ble on`. |
| `combine-findings.py [--in DIR] [--out FILE]` | Reads each `findings-*.json` and writes the matrix. Refer to [Combine](#combine). |

The UART scripts need `pyserial`. The firmware venv (`~/workspace/Firmware/zephyr-env`) has it.

The BLE radio is off at every boot. It comes on only from the Bluetooth menu row or the `ble on` console command. So after a reset, the board does not advertise until something sends `ble on`. `reset-board.py` sends it, and `uart-capture.py --ble-on` sends it through the port that it holds. `serial-monitor.py` does not send it.

`reliability-batch.sh` env variables:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `/dev/cu.usbserial-1120` | Board UART. |
| `FW_DIR` | `$HOME/workspace/Firmware` | West workspace, for `reflash`. |
| `PY` | `$FW_DIR/zephyr-env/bin/python` | Python with `pyserial`. |
| `ADB` | `$HOME/Library/Android/sdk/platform-tools/adb` | adb. |
| `PKG` | `com.snapshotpb.configuration` | App id. |
| `N` | `20` | Iterations. |
| `MAX_FAILS` | `3` | Stop after this number of CONNECT_FAIL results. |
| `RESET_MODE` | `serial` | `serial` (approximately 2 s) or `reflash` (approximately 14 s, restores the default NVS). Use `reflash` only when the write path or a clean state is important. |
| `BOOT_WAIT` | `8` | Seconds for the board to boot and advertise. |
| `OUT` | `/tmp/ble-validation/run-<ts>` | Summary and per-run `test-*.log` and `logcat-*.log`. |

A run is CONNECT_FAIL if the logs show `GATT_ERROR(133)`, `reason=0x003e` or `bta_gattc_open_fail`, or if "Finish Configuration" did not appear.

```bash
N=20 RESET_MODE=serial ./tools/ble-validation/reliability-batch.sh      # screen
N=50 MAX_FAILS=99 ./tools/ble-validation/reliability-batch.sh           # confirm
N=100 MAX_FAILS=99 ./tools/ble-validation/reliability-batch.sh          # sign-off
```

## Android

### ConnectReliabilityE2ETest

`app/android/src/androidTest/.../ConnectReliabilityE2ETest.kt` does no UI automation. It logs under the tag `RELIABILITY`.

| Arm | What it does |
|---|---|
| `bluefalcon` | The production BlueFalcon `AndroidEngine`, made as the app DI makes it. One connect attempt per iteration. |
| `raw` | A minimal `BluetoothGatt` connect that holds the handle and calls `disconnect()` and `close()` each iteration. It logs the exact GATT `connStatus`. This is the control. |
| `leak` | Phase 1 opens `count` direct connects and does not close them. Then it closes the central handles. Phase 2 probes with a clean connect every `probeGapMs`, up to `probeCount` times. Output: `SUMMARY arm=leak leaked=.. recovered=true\|false recoveredAtProbe=N`. Keep `count` below approximately 30 (the Android clientIf limit). |

| Argument | Default |
|---|---|
| `arm` | `bluefalcon` (`bluefalcon`, `raw` or `leak`) |
| `count` | `30` |
| `autoConnect` | `false` |
| `connectTimeoutMs` | `5000` (the app `CONNECT_ATTEMPT_TIMEOUT`) |
| `settleMs` | `750` (the app retry back-off) |
| `name` | `Snapshot PB` (scan name substring) |
| `address` | `AC:EB:E6:8B:07:3A` (pins a MAC and skips the name scan) |
| `mtu` | `false` (raw arm: request an MTU after the connect) |
| `probeCount` | `8` (leak arm) |
| `probeGapMs` | `2500` (leak arm) |

### Build and install

Set Bluetooth and Location to on. Fire OS needs Location for a BLE scan.

```bash
./gradlew :app:android:assembleDebug :app:android:assembleDebugAndroidTest
"$ADB" install -r -t app/android/build/outputs/apk/debug/android-debug.apk
"$ADB" install -r -t app/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
```

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall `com.snapshotpb.configuration` and `com.snapshotpb.configuration.test` first.

`ConnectReliabilityE2ETest` and `OtsSessionE2ETest` show no screen. Android 10 and 11 give scan results only to an app in the foreground, unless the app has the background location permission.
The debug build declares `ACCESS_BACKGROUND_LOCATION` for these two versions (`app/android/src/debug/AndroidManifest.xml`), and the two tests grant it.
A test fails at its start with a clear message when the app does not have the permission. This occurs when the installed debug APK is older than the permission, or when the installer did not permit it. `adb install` permits it.
Then install the debug APK again with `adb install`. On these versions, the logcat line `Need ACCESS_FINE_LOCATION permission to get scan results` shows that Android refused the scan results.

### Measurement runs

Do not reset the board in a loop. Reset the board between arms. Capture the UART as [Capture, reset, correlate](#capture-reset-correlate) shows.

```bash
# Mode B per-attempt: screen, confirm, sign-off
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh screen  arm=bluefalcon count=20
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh confirm arm=bluefalcon count=50
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh signoff arm=bluefalcon count=100

# Mode A A/B: the same count, time-out, settle time and placement; reset the board before each arm
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh ab-raw arm=raw        count=100 connectTimeoutMs=5000 settleMs=750
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh ab-bf  arm=bluefalcon count=100 connectTimeoutMs=5000 settleMs=750

# Leak (sensitivity) arm; reset the board after it
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
./tools/ble-validation/android-reliability-loop.sh leak arm=leak count=24 settleMs=750 connectTimeoutMs=5000 probeCount=8 probeGapMs=2500
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120

# User-visible path (full UI test, 3 retries)
python3 tools/ble-validation/reset-board.py /dev/cu.usbserial-1120
"$ADB" shell am instrument -w -r \
  -e class com.snapshotpb.configuration.ConnectivityE2ETest \
  com.snapshotpb.configuration.test/androidx.test.runner.AndroidJUnitRunner
```

### OtsSessionE2ETest

`OtsSessionE2ETest` runs the OTS session through the production Android `BluetoothManager`, with no UI. It runs on a locked phone.
Each cycle connects with the full OTS read, writes a new boot text, checks the write result, and disconnects.
At the end, it writes the original boot text back (or `finalBootText`). It logs under the tag `OTS_E2E`.
Arguments: `cycles` (default 5), `boardAddress` (default `AC:EB:E6:8B:07:3A`), `finalBootText`.

```bash
./gradlew :app:android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snapshotpb.configuration.OtsSessionE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.cycles=5
```

## Desktop

The runners are in `platform/desktop/bluetooth/src/jvmTest/.../tools/`, so they are not in the desktop app.
The Gradle tasks are not part of `build` or `check`, so CI does not open a radio.
The runner uses the engine that the desktop app selects for the host OS (`MacosJvmEngine`, `WindowsEngine` or `RpiEngine`).
It finds the board by name, because macOS gives a CoreBluetooth UUID, not the MAC.

Use `-PboardName` and `-PboardAddress`. Do not use `-Pname`, because the Gradle `project.name` property hides it.

### bleReliabilityRun

| Property | Default | Meaning |
|---|---|---|
| `arm` | `bluefalcon` | `bluefalcon`, `leak` or `radio-off`. |
| `mode` | `per-attempt` | `per-attempt` or `user-visible` (3 attempts with the retry back-off). `bluefalcon` arm only. |
| `count` | `30` | Iterations. |
| `connectTimeoutMs` | `5000` | Time limit of one attempt. |
| `settleMs` | `750` | Gap between attempts. |
| `boardName` | `Snapshot PB` | Scan name substring. |
| `boardAddress` | `AC:EB:E6:8B:07:3A` | Address match. |
| `probeCount`, `probeGapMs` | `8`, `2500` | Leak arm probes. |
| `uartPort` | none | Board console port for the `radio-off` arm. |
| `python` | `python3` | Python with `pyserial` for `board-console.py` (`radio-off` arm), for example `~/workspace/Firmware/zephyr-env/bin/python`. |

The desktop `leak` arm sends N connects on one peripheral handle without a disconnect. It does not open N separate links as the Android `leak` arm does.
Thus the Bleak control with `--reset none` is the stronger Mode A test on desktop.

The `radio-off` arm is the sensitivity arm for firmware that does not wedge. It connects one time (the warm-up), so the handle holds the OTS services.
Then it sends `ble off` on the board console, makes N clean connects and sends `ble on` at the end.
Its summary is `RELIABILITY SUMMARY arm=radio-off ok=.. fail=.. detected=true|false`. `detected=true` means that each attempt failed.
Record it as a `sensitivity` measurement with `"arm": "radio-off"`, `N` and `fail`. Close other programs that hold the console port first.

Output goes to stdout: `RELIABILITY START ..`, `RELIABILITY arm=.. iter=K result=OK|FAIL detail=services|no-services ms=M t=<epochMs>`,
`RELIABILITY SUMMARY arm=.. ok=.. fail=.. rate=..%`.

```bash
# macOS and Linux
./gradlew -q --console=plain :platform:desktop:bluetooth:bleReliabilityRun \
  -Parm=bluefalcon -Pmode=per-attempt -Pcount=100 -PconnectTimeoutMs=5000 -PsettleMs=750 -PboardName="Snapshot PB" \
  2>&1 | tee /tmp/desktop-$(uname -s)-$(date +%s).log | grep '^RELIABILITY'
```
```bash
# Sensitivity on current firmware: the radio-off arm
./gradlew -q --console=plain :platform:desktop:bluetooth:bleReliabilityRun \
  -Parm=radio-off -Pcount=10 -PuartPort=/dev/cu.usbserial-1110 -PboardName="Snapshot PB" | grep '^RELIABILITY'
```
```bat
:: Windows: put quotes around the full -PboardName argument
gradlew.bat -q --console=plain :platform:desktop:bluetooth:bleReliabilityRun ^
  -Parm=bluefalcon -Pmode=per-attempt -Pcount=100 -PconnectTimeoutMs=5000 -PsettleMs=750 "-PboardName=Snapshot PB" > ble-desktop-windows.log 2>&1
```

### bleOtsE2eRun

`DesktopOtsE2eRunner` connects through the production desktop `BluetoothManager` and does the initial read.
Then each cycle writes a new boot text, reads the board again and checks the text. At the end, it writes the original boot text back.
Output lines start with `E2E `. Properties: `cycles`, `boardName`.
On the dev board, enter `ble on` on the board console first, so that the board advertises.

```bash
./gradlew -q --console=plain :platform:desktop:bluetooth:bleOtsE2eRun -Pcycles=5 -PboardName="Snapshot PB"
```

### Host setup

If the setup is not correct, `scan()` can find nothing, and the resolve step times out.

| Host | Setup | Board UART |
|---|---|---|
| macOS (`MacosJvmEngine`) | Give the terminal app Bluetooth access in System Settings > Privacy & Security (TCC). Run the task one time manually and accept the prompt before a batch. | `/dev/cu.usbserial-*` |
| Windows (`WindowsEngine`) | Use Windows 10 or later. Set the radio to on. If a scan does not find `Snapshot PB`, pair the board in the Windows Bluetooth settings. Run the console as administrator. | `COMx` (pyserial accepts `COM3`) |
| Linux (`RpiEngine`, BlueZ) | Make sure that `bluetoothd` runs and the adapter is on (`bluetoothctl power on`). Give the JVM the capabilities with `sudo setcap 'cap_net_raw,cap_net_admin+eip' $(readlink -f $(which java))`, or use a user that BlueZ permits. Add the serial user to the `dialout` group. | `/dev/ttyUSB0` |

### Bleak control (raw arm on desktop)

The Firmware repository has a Bleak central that runs on macOS, Linux and Windows. Run it on the host under test, with the same board and geometry.

```bash
python3 ~/workspace/Firmware/sample-clients/gatt_test_app/connect_reliability.py --reset serial   # Mode B control: board reset per attempt
python3 ~/workspace/Firmware/sample-clients/gatt_test_app/connect_reliability.py --reset none     # Mode A control: no reset, as the BlueFalcon arm
```

Other options: `-n/--count` (default 20), `--max-fails` (default 3), `--connect-timeout`, `--port`, `--name`, `--address`, `--serial-capture LOGFILE`, `--out SUMMARY`.
`CONNECT_RELIABILITY.md` next to the script has the full description. Record the OK and FAIL counts as a `raw-control` measurement.
If the Bleak control stops on macOS during fast cycles, set Bluetooth off and on, or use `--reset serial`.

## iOS

The XCUITest target `iosAppUITests` (`iosApp/iosAppUITests/SnapshotPBUITests.swift`) holds the hardware tests.
`iosApp/add_uitest_target.rb` adds the target and a shared scheme to the Xcode project. You can run it again safely.
The script needs the `xcodeproj` gem 1.27 or later (`gem install --user-install xcodeproj`), because older versions cannot read Xcode 16 synchronized-folder projects.

### Phone and Mac setup

- Xcode > Settings > Accounts has the Apple ID of team `S9MGTJXVTV`, so `-allowProvisioningUpdates` can make the profiles.
- On the phone: Developer Mode on, Settings > Developer > Enable UI Automation on, and the developer certificate trusted (Settings > General > VPN & Device Management).
  The first automation run asks for the phone passcode.
- Keep the phone unlocked (Auto-Lock: Never). The tests set portrait orientation.
- Run `xcodebuild` from a Terminal window on the Mac, not over SSH. Code signing needs the unlocked login keychain.

### Tests

| Test | What it proves | Needs |
|---|---|---|
| `testHardware_connectToBoard` | Scan, the Bluetooth permission prompt, the 4-step connect. Then it taps Finish Configuration, so the board is saved. | Run with `FRESH=1`, so the app has no saved board. |
| `testHardware_reconnectToBoard` | Connect, disconnect and connect again in one app session. With `RECONNECT_DELAY_SEC` and `RESET=1`, the board restarts in between, so the cached GATT handles are stale. | A saved board. |
| `testHardware_linkLossShowsDisconnected` | The card shows Disconnected when the board resets while it is connected. | A saved board, `RESET=1`. |
| `testHardware_writeBootText` | A config write, a board restart, and a reconnect with no conflict. It writes the original value back. | A saved board, `RESET=1`. |
| `testHardware_writeProfileName` | The same for a profile name. `TEST_RUNNER_PROFILE_NAME` names the profile (default `Default`). | A saved board, `RESET=1`. |
| `testHardware_photoFromCamera` | Take Photo, the camera permission prompt, the shutter, Use Photo. | A saved board. |
| `testHardware_photoFromGallery` | Select from Gallery and pick the first library item (a HEIC Live Photo works). | A saved board, and one photo in the library. |

After a write test, the board boot log in `<label>-<i>.board-uart.log` shows the value from flash: `Saved device config found (… boot_text=…)` and `Profile 0: name=…`.
After a photo test, list the app data container to see the stored `device_photo_*` file:
`xcrun devicectl device info files --device $UDID --domain-type appDataContainer --domain-identifier com.snapshotpb.configuration.SnapshotPB`.

The app Arbor logs run on a background dispatcher, and they do not show in `devicectl --console`. Thus the app shows the connect result in the
`connProgress:<s1>-<s2>-<s3>-<s4>|err=<code>` test tag, and the connect test prints it.
The code is a short token, for example the simple class name of the exception (`err=OtsObjectNotFound`).
The tag does not hold the exception message, because on iOS the tag is the accessibility identifier, also in a release build.

### Commands

```bash
UDID=<device-udid>   # xcrun devicectl list devices
cd iosApp && xcodebuild build-for-testing -project iosApp.xcodeproj -scheme iosAppUITests \
  -destination "platform=iOS,id=$UDID" -allowProvisioningUpdates && cd ..

export UDID
FRESH=1 tools/ble-validation/ios-hardware-loop.sh connect 20 testHardware_connectToBoard
RESET=1 RECONNECT_DELAY_SEC=20 tools/ble-validation/ios-hardware-loop.sh reconnect-reset 5 testHardware_reconnectToBoard
RESET=1 tools/ble-validation/ios-hardware-loop.sh linkloss 3 testHardware_linkLossShowsDisconnected
RESET=1 tools/ble-validation/ios-hardware-loop.sh writeboot 3 testHardware_writeBootText
RESET=1 tools/ble-validation/ios-hardware-loop.sh writeprofile 3 testHardware_writeProfileName
tools/ble-validation/ios-hardware-loop.sh photocam 2 testHardware_photoFromCamera
tools/ble-validation/ios-hardware-loop.sh photogal 2 testHardware_photoFromGallery
```

With `BOARD_NAME`, the connect test taps the board with that name. An unsaved board shows the default name only in its label, and the test matches the label too.

The iOS side has no headless reliability arm (BlueFalcon, raw `CBCentralManager` or leak) at this time. Use a Bleak control on the host Mac as the raw arm.

## Findings

Each platform, host and central writes one file: `tools/ble-validation/results/findings-<platform>-<host>.json`.
`platform` is one of `android`, `ios`, `desktop-macos`, `desktop-windows`, `desktop-linux`.
The combiner identifies a file by `platform`, `host.machineId` and `central.id` (or `central.model`), not by the file name.
Give each host a unique `machineId`. If two files have the same identity, the combiner stops with an error.
Add a new run to the same file as a new measurement. Do not replace the older measurement.
The `results/` directory does not exist until you add the first findings file. `combine-findings.py` makes it if necessary.
Keep large logcat and UART captures on the host (`/tmp/ble-validation/...`). Put only the JSON and small summaries in git.

```json
{
  "schemaVersion": 3,
  "platform": "android",
  "engine": "dev.bluefalcon.engine.android.AndroidEngine",
  "engineVersion": "3.7.14",
  "enginePatched": false,
  "engineArtifact": "dev.bluefalcon:blue-falcon-engine-android:3.7.14",
  "engineDistinctCoordinate": true,
  "engineProvenance": "Maven Central",
  "host":    { "os": "macOS", "osVersion": "15.5", "machineId": "mac-host-01" },
  "central": { "model": "Amazon Fire Max 11 (KFMUWI)", "osVersion": "Fire OS 7.3.3.1 / Android 9 / API 28", "id": "<adb serial>" },
  "board":   { "addr": "AC:EB:E6:8B:07:3A", "firmwareBuild": "diag", "firmwareConf": "Firmware/app/debug-ble.conf", "firmwareCommit": "<sha>", "uart": "/dev/cu.usbserial-1120" },
  "rf":      { "geometry": "through one interior wall ~6m", "observedRssi": -78 },
  "appCommit": "<sha>",
  "dateStart": "2026-06-27", "dateEnd": "2026-06-27", "tester": "<name>",
  "baselineNonZero": true,
  "measurements": [
    { "mode": "sensitivity", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "leak", "N": 24, "ok": null, "fail": null, "ratePct": null, "wedgeObserved": true, "wedgeAtIter": 0, "recoveredByBoardReset": true, "rawLogPath": "/tmp/ble-validation/android-reliability/leak-<ts>.logcat", "notes": "" },
    { "mode": "raw-control", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "raw", "N": 100, "ok": 76, "fail": 24, "ratePct": 24.0, "wedgeObserved": false, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "...", "notes": "" },
    { "mode": "A-wedge", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "prod", "N": 100, "ok": 77, "fail": 23, "ratePct": 23.0, "wedgeObserved": false, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "...", "notes": "" },
    { "mode": "B-perattempt-screen", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "prod", "N": 20, "ok": 15, "fail": 5, "ratePct": 25.0, "wedgeObserved": null, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "...", "notes": "" },
    { "mode": "B-perattempt-confirm", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "prod", "N": 50, "ok": 39, "fail": 11, "ratePct": 22.0, "wedgeObserved": null, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "...", "notes": "" },
    { "mode": "B-signoff", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "prod", "N": 100, "ok": 74, "fail": 26, "ratePct": 26.0, "wedgeObserved": null, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "...", "notes": "" },
    { "mode": "user-visible", "date": "2026-06-27T14:05Z", "firmware": "diag", "arm": "prod", "N": 100, "ok": 97, "fail": 3, "ratePct": 3.0, "wedgeObserved": null, "wedgeAtIter": null, "recoveredByBoardReset": null, "rawLogPath": "/tmp/ble-validation/run-<ts>", "notes": "" }
  ],
  "verdict": "clean",
  "verdictNotes": ""
}
```

Field rules:

- `mode` is one of `sensitivity`, `raw-control`, `A-wedge`, `B-perattempt-screen`, `B-perattempt-confirm`, `B-signoff`, `user-visible`.
- `arm` is one of `prod`, `raw`, `leak`.
- `date` is the UTC start time of the batch in ISO 8601 (`2026-06-27T14:05Z` or `2026-06-27`). Use one format in a file.
- `firmware` is `diag` or `ship`. If it is not there, the combiner uses `board.firmwareBuild`.
- `ratePct` is `100 * fail / N`. The combiner calculates the rate from `fail` and `N`.
- The `wedge*` fields apply only to `A-wedge`, `sensitivity` and `raw-control`. Record `wedgeObserved` for the raw control too.
- `baselineNonZero` is optional. A `raw-control` measurement with `fail > 0` proves the baseline.
  Set `baselineNonZero: true` only when a different baseline (for example, an engine that leaks connect handles) failed in the same geometry.
- Schema 2 files (no `date` and no `firmware` in a measurement) still load.
- `enginePatched: true` with `engineDistinctCoordinate: false` means that a local patched engine has the same version as the stock engine. A build without the local copy then gets the stock engine.
- `engineProvenance` records the source of the engine, and the checksum proof for a patched engine.

To make a summary from a local capture:

```bash
mkdir -p tools/ble-validation/results/summaries/<platform>-<host>
grep -E "RELIABILITY: (START|arm=|SUMMARY)" /tmp/ble-validation/android-reliability/<label>-*.logcat \
  > tools/ble-validation/results/summaries/<platform>-<host>/<label>.summary.txt
```

## Combine

Run the combiner from the repository root. The defaults are `--in tools/ble-validation/results` and `--out tools/ble-validation/results/matrix.md`.

```bash
python3 tools/ble-validation/combine-findings.py
```

Matrix columns: `Platform | Host | Central | Engine | Patched? | Mode A wedge? | Raw control rate | Per-attempt rate | User-visible | Verdict | Findings file`.
The matrix has one row for each findings file. A rate cell shows the firmware of the measurement, for example `26.0% (26/100, diag)`.

For each mode, the combiner uses the newest measurement: the latest `date`, or the last one in the list if the dates are the same or absent.
Thus a new run, for example the final run on the shipping firmware, replaces the older run of that mode.
A production rate is compared with the newest raw control on the same firmware. If there is none, it is compared with the newest raw control.

The combiner sets the verdict. The `verdict` field in the JSON is only a cross-check. A row is GREEN only if all of these conditions are true:

- A `raw-control` measurement has `fail > 0`, or the file has `baselineNonZero == true`. If not, the verdict is INCONCLUSIVE.
- The newest `sensitivity` measurement has `wedgeObserved == true`, or it is a radio-off run (`"arm": "radio-off"`) with `N >= 10` and `fail == N`. This shows that the harness can see a failure.
- A `raw-control` measurement exists and its `wedgeObserved` is not `true`. This shows that the control is valid.
  Per-attempt fails of the control are Mode B, and they are necessary at the marginal geometry.
- An `A-wedge` measurement has `wedgeObserved == false`. If it is `true`, the verdict is LEAK.
- A `B-signoff` measurement has `N >= 100` (`N >= 20` for iOS), and its rate is not higher than the raw control rate.
- A `user-visible` measurement has `N >= 100` (`N >= 20` for iOS), and its rate is not higher than the raw control rate.
- If `enginePatched == true`, then `engineDistinctCoordinate == true`. If not, the verdict is AMBER.

If one of the other conditions is false, the verdict is FAIL.

"Not higher" uses a one-sided Fisher exact test at `ALPHA = 0.05`. The rate fails the gate only when the probability of so many fails,
with the control rate, is less than 5 %. Thus a correct engine fails the gate by chance in approximately 1 run of 20.
At N=100 and a control rate of 25 %, the test finds a production rate that is approximately 12 points higher.
It does not find a smaller difference. The `A-wedge` measurement finds the known engine fault (Mode A) directly.

To run the unit tests of the combiner:

```bash
python3 -m unittest discover -s tools/ble-validation/tests
```

The `scripts` job of `.github/workflows/validation.yml` runs these tests when a pull request changes `tools/ble-validation/`.
