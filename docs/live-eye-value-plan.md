# Live eye value next to the eye threshold

This document is the plan to show the live value of the breach eye next to the eye threshold
setting. With this value, a user can set the threshold without a trial on the field. The
document gives the facts in the code today, the owner decisions, the work items in order, the
tests, the risks and the division into parallel work. The research date is 2026-11-21. A
review on the same date compared the plan with the code; section 8 lists its changes.

## 0. Goals

1. While the board is connected, the profile editor shows the live eye level next to
   `EYE_THRESHOLD`, in the same percent scale as the threshold.
2. The editor shows the result "Ball" or "Empty" for the edited threshold, before a save.
3. When the board cannot give a value, the editor shows a hint that tells the user why.
4. The Android, desktop, iOS, web, Wear and Zepp apps all show the value.

These are not goals: a value on the board display, a graph of the value over time, and a
"set from reading" button. Section 6 tells why.

## 1. Facts in the code today

| Fact | Value | Source |
|---|---|---|
| Live eye value on the wire | The board sends no eye value. It has no OTS object, no notification and no SMP group for it. The OTS objects are `device`, `profiles` and `config` | `Configuration-Protocol/snapshotpb/v1/protocol.proto`; `Firmware/rust/crates/snapshot-ots-fsm/src/lib.rs:543` |
| SMP groups | The board has the OS group (0) and the image group (1) only | `Firmware/rust/crates/snapshot-smp/src/os.rs`, `img.rs` |
| Eye sample in the firmware | The Rust sampler reads the eye each 500 µs and keeps the last sample in `EyeState.last_sample` (`level`, `threshold`, `ball_present`). The C firmware has the same poller in `fire_control_chamber_eye_adc_thread` | `Firmware/rust/crates/snapshot-app/src/coordinator/sampler/eye.rs:56`; `Firmware/drivers/fire_control/fire_control_chamber_thread.c:399` |
| Sample scale | The sample is a raw 12-bit value (0 to 4095) | `eye.rs:56` |
| Threshold scale | The profile stores a percent (0 to 100, 0 selects the default of 50, a value above 100 becomes 100). The firmware changes it to raw counts with `pct * 4095 / 100` | `Firmware/rust/crates/snapshot-config/src/wire_presence.rs:499` |
| Ball rule | `BREAK_BEAM`: a ball is present when the sample is at or below the threshold. `REFLECTIVE`: a ball is present when the sample is above the threshold | `Firmware/rust/crates/snapshot-core/src/chamber.rs:120` (`fc_eye_ball_present`) |
| Protocol comment | The comment on `eye_threshold` says "below this threshold" for all modes. That is not correct for `REFLECTIVE` | `Configuration-Protocol/snapshotpb/autococker/v1/autococker.proto:40` |
| Emitter | The emitter is on only when the active profile of the board has an eye mode and the eyes override is on. At all other times, `last_sample` is `None`. The fire mode is not a part of this rule | `eye.rs:9`; `snapshot-core/src/modes.rs:90` |
| Sample gaps | While the eye is active, a tick gives no sample during the settle time of 1 ms and after a failed ADC read. `last_sample` then keeps the sample before it | `eye.rs:263` (`eye_acquire`) |
| Eye fault | An ADC channel that is absent or not ready sets `adc_failed` and `FC_FAULT_CHAMBER_THREAD`. The eye then stays off until the next boot | `eye.rs:306` |
| Eyes override | A long push of UP on the home screen of the board turns the eyes off or on. The board does not save it, so it is on after each boot. It has an effect only when the active profile has an eye mode | `Firmware/app/src/menu_profiles.c:87` |
| GATT table of the board | The C table has the handles 1 to 47 in five services. The Rust port makes the same table by hand and tests it against a capture of the C table: each handle, each CCC handle and the Database Hash. The bond record keeps the value of each CCC handle | `Firmware/rust/crates/snapshot-app/src/ble/gatt.rs:1`, `:144`, `:861`; `ble/bonds.rs:39` |
| Connections | The board accepts one connection (`CONFIG_BT_MAX_CONN=1`) | `Firmware/app/prj.conf:23` |
| Vendor UUIDs in use | `00000001-5350-4221-0000-5350425f4f54` (device object type) and `00000002-…` (object data) | `core/bluetooth/.../ots/OtsUuids.kt` |
| App editor | `ProfileScreen` shows `EYE_THRESHOLD` in the eye section through the `MarkerProfileField` table, only when `profile.usesEye` is true. Wear shows it in `ProfileFieldStepperScreen`. Zepp shows it through `profileFields.js` | `core/common/.../screen/profile/ProfileScreen.kt:462`; `core/model/.../MarkerProfileField.kt:74`; `app/zepp/src/utils/profileFields.js:148` |
| Profile ViewModel | `ProfileViewModel` has no Bluetooth dependency today | `core/common/.../screen/profile/ProfileViewModel.kt` |
| Pattern for a notify subscription | `BluetoothConnectionEngine.subscribeBoardLog` finds NUS TX by UUID through `PlatformBluetoothSeams.serviceWithCharacteristics`. A failure records a state and does not fail the connect. `servicesFollowPermission` tells a service that the web grant hides (`NOT_VISIBLE`) from a service that the board does not have (`ABSENT`) | `core/bluetooth/.../BluetoothConnectionEngine.kt:557` |
| Request bus | `BluetoothRequestManager.bus` has no buffer. A `send` with no collector loses the request. Five apps and two simulated boards each have a `when` on `BluetoothRequest` | `core/bluetooth/.../BluetoothRequestManager.kt`; `MainActivity.kt` (Android, Wear), `Application.kt` (desktop), `main.kt` (browser), `MainViewController.kt` (iOS), `FakeBoard.kt`, `WearFakeBoard.kt` |
| Viewer bridge | `window.snapshotBoard` is a GATT bridge: `gatt()`, `read`, `write`, `subscribe(handle, mode)` and value events. `EmulatorEngine` gives the app each service that the firmware model has | `platform/browser/bluetooth/.../emulator/BoardBridge.kt` |
| Zepp GATT profile | `BleManager._buildGattProfile` gives `mstBuildProfile` one fixed list: the OTS service and its characteristics. A failed build fails the connect. `OtsClient.js` sets the one `mstOnCharaNotification` callback | `app/zepp/src/bluetooth/BleManager.js:620`; `OtsClient.js:77` |
| Zepp proto build | `build-proto` gives `pbjs` each `.proto` file of `snapshotpb/v1`, so a new file there needs no script change | `app/zepp/package.json:7` |

The eye level is a value of the sensor, not of a profile. Thus the app can compare the live
level with the threshold that the user edits, also before a save, and also for a profile that
is not active on the board.

## 2. Owner decisions (2026-11-21)

1. **Transport.** A new vendor GATT notify characteristic sends the eye status. The app does
   not poll a new OTS object, because each poll competes with the profile writes for
   `writeMutex`. The app does not use an SMP group, because the macOS desktop and the web
   have no SMP write (`UNSUPPORTED_PLATFORM`).
2. **Emitter.** The board rule does not change. The firmware does not turn on the emitter for
   the app. When the emitter is off, the app shows a hint.
3. **Firmware.** The C firmware and the Rust port both get the characteristic, with the same
   UUIDs and the same message.
4. **Scope.** One app pull request covers all apps: the shared UI (Android, desktop, iOS,
   web), `FakeBoard`, `EmulatorEngine`, Wear and Zepp.

## 3. Work items in order

### Step 1. Protocol (Configuration-Protocol)

1. Add `snapshotpb/v1/eye_status.proto` with the message `EyeStatus`:
   - `optional uint32 level_raw`: the last sample. The field has presence, because 0 is a
     valid level.
   - `uint32 full_scale_raw`: 4095 for the 12-bit ADC.
   - `EyeSensorState state`: `EYE_SENSOR_STATE_UNSPECIFIED`, `EYE_SENSOR_STATE_ACTIVE`,
     `EYE_SENSOR_STATE_EYE_OFF` (the active profile has eye mode `OFF`),
     `EYE_SENSOR_STATE_OVERRIDE_OFF` (the eyes override is off), `EYE_SENSOR_STATE_FAULT`
     (the eye channel latched a fault).
2. The message has no `active_profile` field. No screen of this plan uses it, and the app
   has the active profile from `DeviceConfig`.
3. The board selects the state in this order: `FAULT`, then `EYE_OFF`, then `OVERRIDE_OFF`,
   then `ACTIVE`. Thus the hint names the first thing that the user must change.
4. The board sets `level_raw` only in the state `ACTIVE`. In `ACTIVE`, the board omits
   `level_raw` until the first sample after the settle time.
5. A client ignores an unknown state value and shows no level for it.
6. The UUIDs are `00000010-5350-4221-0000-5350425f4f54` for the service and
   `00000011-5350-4221-0000-5350425f4f54` for the characteristic. Write them in the README.
7. Add the golden file `testdata/eye_status.json`: for each case, the field values and the
   encoded bytes in hex. The cases are `ACTIVE` with level 0, `ACTIVE` with level 4095,
   `ACTIVE` with no level, and each other state. The C, Rust, Kotlin and Zepp tests all read
   this one file.
8. Correct the comment on `eye_threshold`. Give the ball rule for each eye mode.
9. Add the nanopb options, if a field needs one.
10. Run `buf lint`.

### Step 2. Firmware (C and Rust)

1. Add one vendor GATT service with one characteristic. The characteristic has the read and
   notify properties. Its permissions are the same as those of NUS TX and its CCC.
2. Put the service after the SMP service, at the handles 48 to 51: the service declaration,
   the characteristic declaration, the value and the CCC. The handles 1 to 47 must not
   change. In C, the service variable name sets the link order, so use a name that sorts
   after `smp_bt_svc` (for example `snapshot_eye_svc`).
3. Make the C capture again (`scripts/ble_parity/reference/c-esp32c3_devkitm.json`). Then
   update the Rust table, `CCC_HANDLES`, the table tests and the Database Hash test from the
   new capture.
4. Make sure that a bond record of the release before this change still loads. Its client
   then has an old Database Hash, so the board sends Service Changed (`bonds.rs:50`). Add a
   test for this case.
5. The sampler copies the last sample and the state into a shared atomic value. The BLE task
   reads that value. The 500 µs sampler gets no other new work.
6. While a client has notifications on (CCC), the board sends `EyeStatus` at approximately
   10 Hz. The board sends no notification when no client has notifications on.
7. The CCC value of the eye characteristic goes back to 0 at each disconnect, also for a
   bonded client. Thus the board never starts the 10 Hz notifications by itself.
8. The board sends no eye notification during an SMP upload.
9. When the emitter is off, the board sends the state `EYE_OFF` or `OVERRIDE_OFF` with no
   level.
10. Add a test of the encoded bytes for each firmware against the golden file of step 1.7.
11. On the bench, measure the cycle time of the marker with notifications on and with
    notifications off. The two values must agree in the limits of the fire control tests.

### Step 3. App, contract commit (`core:model`, `core:bluetooth` API, `core:resources`)

This commit is first on the feature branch. Each other app chunk builds on it.

1. Add `EyeReading(levelRaw: Int?, fullScaleRaw: Int, state: EyeSensorState)` to
   `core:model`.
2. Add `eyeSeesBall(eyeMode: MarkerEyeMode, levelRaw: Int, fullScaleRaw: Int,
   thresholdPercent: Int): Boolean?` to `core:model`. It uses the firmware rule and the scale
   `pct * fullScaleRaw / 100` with integer division, 0 as the default of 50, and 100 as the
   maximum. It returns `null` for the eye mode `OFF`.
3. Add `eyeThresholdRaw(fullScaleRaw: Int, thresholdPercent: Int): Int`, so that the bar
   draws the threshold mark from the same raw value as the result.
4. Add the fixture `core/model/src/jvmTest/resources/eye-ball-rule.json`. The Kotlin test and
   the Zepp test both read it.
5. Add `EyeReadingState` to `core:bluetooth`: `Waiting` (subscribed, no value in the last
   second), `Reading(EyeReading)`, `Unsupported` (the board has no such characteristic),
   `NotPermitted` (the web grant hides the service) and `Failed` (the subscribe failed).
6. Add `BluetoothStateManager.eyeReading(deviceId: String): Flow<EyeReadingState?>`. The
   value is `null` while the board is not connected. A collector of this flow is a watcher.
   There is no `WatchEye` request and no `UnwatchEye` request: the request bus can lose a
   request, a cancelled scope cannot send the unwatch, and each app `when` would need a new
   branch.
7. Add `BluetoothStateManager.eyeWatcherCount(deviceId)` and `setEyeReading(deviceId, state)`
   for the engine and for the simulated boards.
8. Add each string of steps 5.4 and 5.5 to `values/strings.xml` and to each `values-*`
   locale.
9. `EyeReading` is in memory only. It is not in `devices.json` and not in the Wear sync, so
   `DEVICE_SYNC_SCHEMA_VERSION` does not change.

### Step 4. App, `core:bluetooth` engine

1. Add `EyeStatusUuids` beside `BoardLogUuids`.
2. After the connect, find the characteristic by UUID through
   `PlatformBluetoothSeams.serviceWithCharacteristics`. With no service, record
   `NotPermitted` when `servicesFollowPermission` is true, and `Unsupported` if not. The
   connection does not fail.
3. Turn notifications on when the watcher count goes above zero. Turn them off 2 seconds
   after it goes back to zero, so that a screen rotation does not write the CCC two times.
4. Hold `writeMutex` for each CCC write. Do not turn notifications on during a firmware
   upload, and turn them off when an upload starts.
5. Decode `EyeStatus` and record `Reading`. If no notification comes for 1 second, record
   `Waiting`, so that the editor does not show an old level as a live level.
6. The teardown removes the entry of the board.
7. The Bluetooth log records the subscribe event and the unsubscribe event, with a fixed code
   for a failure. It does not record each reading.

### Step 5. App, shared UI (`core:common`)

1. The profile screen collects `eyeReading(deviceId)` only while the screen is started
   (lifecycle) and `profile.usesEye` is true for the edited profile. An app in the background
   is thus no watcher. A delegate does this work, and `ProfileViewModel` stays thin.
2. `ProfileContent` gets an `eyeReading` parameter, so the layout tests need no Bluetooth.
3. Next to `EYE_THRESHOLD`, for `Reading` with the state `ACTIVE` and a level, the editor
   shows:
   - the live level in percent of the full scale, with one decimal;
   - a bar with the level and a mark at `eyeThresholdRaw` of the edited threshold;
   - "Ball" or "Empty", from `eyeSeesBall` with the edited eye mode and threshold.
4. For each other state, the editor shows a hint:
   - `EYE_OFF`: "Select a profile with an eye on the board to see the eye value."
   - `OVERRIDE_OFF`: "Turn the eyes on at the board to see the eye value."
   - `FAULT`: "The board reports an eye fault."
   - `Unsupported`: "Update the firmware to see the live eye value."
   - `NotPermitted`: "Connect to the board again with the browser chooser to see the eye
     value."
   - `Waiting`, `Failed`, `ACTIVE` with no level, and not connected: no hint and no value.
5. The level text is not a live region for a screen reader, because it changes 10 times
   each second. The "Ball" or "Empty" text is a polite live region.

### Step 6. App, other engines and platforms

1. `FakeBoard`: while it is connected and the watcher count is above zero, it records a
   simulated level that moves up and down. Thus the Demo entry shows the feature with no
   hardware. It needs no new request.
2. Web: add the service UUID to `scanFilters`. An old grant gives no access to the new
   service, so the app shows the `NotPermitted` hint until the user connects again with the
   chooser.
3. `EmulatorEngine`: the viewer bridge does not change. The bridge carries GATT, so the
   engine gives the app the new service when the firmware model in the viewer has it. With
   an old model, the engine reports `Unsupported`. Add a test with a fake `BoardBridge` for
   each case.

### Step 7. Wear (`app:wear`)

1. `ProfileFieldStepperScreen` shows the live value and the result for `EYE_THRESHOLD`. It
   collects `eyeReading(deviceId)` only while that field is on the screen.
2. The Wear app uses `platform:android:bluetooth`, so it gets the engine change of step 4.
3. `WearFakeBoard` records a simulated level, as `FakeBoard` does.

### Step 8. Zepp (`app/zepp`)

1. First, do a test on a watch: add the eye service to the list of `mstBuildProfile` and
   connect to a board with the old firmware. Record the result in this plan.

   **Status (2026-11-21): the watch test is still open.** It needs the owner, a watch and a
   board with the old firmware. Chunk A5 did not wait for it. The app has a path for each
   possible result (item 2). Record here which result the watch gives, then remove the paths
   that the watch does not use.
2. If the build fails for a board with no eye service, build the profile again with the OTS
   service only, and mark the board as `Unsupported`. A board with the old firmware must
   still connect.

   Chunk A5 did this for three possible results. `BleManager._buildGattProfile` first asks
   for the OTS service and the eye service.
   - (a) The build calls `fail`: the app builds the profile again on the same link, with the
     OTS service only.
   - (b) The build succeeds, but the OTS subscribes of `OtsClient.prepare()` fail: the app
     builds the profile again on the same link, with the OTS service only.
   - (c) The build succeeds, and only the eye CCC write fails: the app shows `Unsupported`.
     A CCC write that times out shows `Failed` (no hint).

   After (a) or (b), the board is `Unsupported`. The app keeps the board address in memory
   and uses the OTS-only profile for the next connects to it, until the app starts again.
3. The one `mstOnCharaNotification` callback of `OtsClient.js` sends each value to its
   module by the characteristic UUID.
4. The BLE layer turns the eye notifications on when the `EYE_THRESHOLD` stepper opens and
   off when it closes.
5. `pbjs` decodes `EyeStatus` from the same `.proto` file.
6. The stepper of `EYE_THRESHOLD` shows the live value and the result. The mock board gives
   a simulated level.
7. Add a mirror of `eyeSeesBall`, with `Math.floor` for the integer division. The test
   reads the fixture of step 3.4 and the golden file of step 1.7.

### Step 9. Documents

1. Update `CLAUDE.md` of the app: the feature list, the Bluetooth section and the web target.
2. Update the Bluetooth documents of the `Firmware` repository with the new table.

## 4. Sequence of the pull requests

1. The protocol pull request.
2. The firmware pull request, for C and Rust.
3. The app pull request, after the protocol merges. The work can start before the firmware
   merges, with `FakeBoard`.

The app shows the `Unsupported` hint for a board with an old firmware. Thus the app can
release before the firmware release.

## 5. Tests

1. Parity of `eyeSeesBall` with `fc_eye_ball_present`, for each eye mode, at the threshold
   value and at one count on each side. Thresholds 0 (the default), 1, 50, 100 and 101. The
   eye mode `OFF` gives `null`.
2. Decode tests of `EyeStatus` with the golden file of step 1.7, in Kotlin and in Zepp.
3. Engine tests: the subscribe at the first watcher, the delay before the unsubscribe,
   `Unsupported`, `NotPermitted`, `Waiting` after 1 second with no value, no subscribe
   during a firmware upload, and the teardown.
4. Screen tests: the screen is a watcher only while it is started and the profile uses the
   eye.
5. Layout tests of `ProfileContent` at the iPhone Duo sizes, with each state.
6. Wear and Zepp tests.
7. Firmware tests: the table and the Database Hash against the new capture, the bond record
   of the old release, the encoded bytes, and no notification with the CCC at 0.
8. A bench test with a board: each state, a ball in the breach for each eye mode, and the
   cycle time of step 2.11.

## 6. Not in this plan

- **A value on the board display.** The board menu has its own owner and plan.
- **A graph of the value over time.** The first version shows one value.
- **The minimum and maximum level of each 100 ms.** The message can get these fields later
  with no break of the protocol.
- **A "set from reading" button.** A later item can set the threshold to the middle point
  between the empty level and the ball level.
- **An emitter on for the app.** The owner selected to keep the board rule (decision 2).

## 7. Risks

1. **Power and radio time.** Notifications at 10 Hz use power. The board sends only while a
   client has notifications on, and the app watches only while the editor is on the screen.
2. **Fire control timing.** The BLE task sends 10 packets each second on a processor that
   also runs the 500 µs sampler. The bench test of step 2.11 measures the effect.
3. **Rounding at the threshold.** The app uses the raw level and the firmware scale, so its
   result is the same as the board result at each count. The level text has one decimal, so
   it can show the same number as the threshold while the result is "Ball".
4. **C and Rust divergence.** The golden file of step 1.7 and the capture of step 2.3 keep
   the two firmwares the same.
5. **Changed GATT table.** A phone can keep the old table of a board. A bonded client gets
   Service Changed. A client with no bond finds the services again at each connect. If a
   platform keeps an old table, the app shows `Unsupported` and all other functions work.
6. **Zepp profile build.** The behavior of `mstBuildProfile` for a service that the board
   does not have is not known. The test of step 8.1 comes first. If no fallback works, the
   owner decides if the Zepp part stays in this pull request.
7. **One sample each 100 ms.** The value is one sample of the 500 µs sampler, so the level
   on the screen can move with the noise of the sensor.

## 8. Changes from the review (2026-11-21)

| Item | Before | Now |
|---|---|---|
| `level_raw` | `uint32`, so no level and level 0 were the same | `optional uint32` |
| `active_profile` | A field with no user | Removed |
| State order | Not given | `FAULT`, `EYE_OFF`, `OVERRIDE_OFF`, `ACTIVE` |
| UUIDs | Not selected | `00000010-…` and `00000011-…` |
| Golden bytes | One test in each firmware | One file in the protocol repository for all four clients |
| GATT table | Not in the plan | Handles 48 to 51, a new capture, the Database Hash, the bond record |
| Bonded CCC | Not in the plan | The eye CCC is 0 at each connect |
| `eyeSeesBall` | Fixed scale of 4095 | Uses `fullScaleRaw`; `null` for the eye mode `OFF` |
| Watch API | `WatchEye` and `UnwatchEye` requests with a count | A collector of `eyeReading(deviceId)` is a watcher |
| Reading states | `Reading`, `Unsupported` | Also `Waiting`, `NotPermitted`, `Failed` |
| Web grant hint | The incorrect "update" hint (old risk 4) | `NotPermitted` with its own hint |
| Old value | Not in the plan | `Waiting` after 1 second with no notification |
| CCC write | Not in the plan | Under `writeMutex`; none during a firmware upload |
| Viewer bridge | A new optional bridge method | No bridge change; the bridge carries GATT |
| Wear demo board | Not in the plan | `WearFakeBoard` gives a level |
| Zepp | Subscribe only | A watch test first, a fallback profile, one notification router |
| Documents | Not in the plan | Step 9 |

## 9. Parallel work

Each chunk is one branch and one worktree. The chunks of one wave have no file in common,
so they can run at the same time.

### Wave 0: the contract (one after the other, short)

| Chunk | Repository | Scope | Done when |
|---|---|---|---|
| P | Configuration-Protocol | Step 1 | `buf lint` passes and the pull request is open |
| A0 | SnapshotPB | Step 3, with the submodule at the branch of chunk P | `:core:model:jvmTest`, `:core:bluetooth:jvmTest` and `:core:resources:localizationCheck` pass |

Chunk A0 makes the feature branch `feat/live-eye-value`. Chunk P and chunk A0 can run at the
same time, because step 1 of this plan gives the full message.

### Wave 1: seven chunks at the same time

| Chunk | Repository | Scope | Files | Needs |
|---|---|---|---|---|
| FC | Firmware | Step 2 for C, and the new capture | `app/src/subsystems/bluetooth`, `drivers/fire_control`, `scripts/ble_parity/reference` | P |
| FR | Firmware | Step 2 for Rust, but not the capture tests | `rust/crates/snapshot-app/src/ble`, `coordinator/sampler` | P |
| A1 | SnapshotPB | Step 4, and step 6.1 | `core/bluetooth`, `core/fake-board` | A0 |
| A2 | SnapshotPB | Step 5 | `core/common` | A0 |
| A3 | SnapshotPB | Steps 6.2 and 6.3 | `platform/browser/bluetooth` | A0 |
| A4 | SnapshotPB | Step 7 | `app/wear` | A0 |
| A5 | SnapshotPB | Step 8 | `app/zepp` | P, and the fixture of A0 |

Chunk A5 has no Kotlin file, so it can start in wave 0 with its watch test (step 8.1).

Chunks A2 and A4 use only the API of chunk A0, with a fake flow in their tests. They do not
wait for chunk A1.

### Wave 2: join and test

| Chunk | Scope | Needs |
|---|---|---|
| FJ | Firmware: merge FC and FR into one branch. Update the Rust table tests and the Database Hash test from the capture of FC (steps 2.3 and 2.4). Open the firmware pull request | FC, FR |
| AJ | SnapshotPB: merge A1 to A5 into `feat/live-eye-value`. Run the five CI targets and the Zepp build. Do step 9.1. Open the one app pull request | A1 to A5, P merged |
| B | Bench test with a board (step 5.8 of the tests), with the firmware of FJ and the app of AJ | FJ, AJ |

### Conflict points

- `strings.xml` of each locale: only chunk A0 changes them.
- `BluetoothStateManager.kt`: only chunk A0 changes the API. Chunk A1 changes the engine.
- `gatt.rs` handles and `CCC_HANDLES`: chunk FR adds them from step 2.2. Chunk FJ makes them
  agree with the capture.
- The submodule pointer: only chunk A0 moves it. Chunk AJ moves it to the merge commit of
  chunk P.
