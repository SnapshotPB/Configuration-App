# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin Multiplatform (KMP) configuration app for Snapshot PB paintball boards on Autococker e-framed devices. It connects to a marker board over Bluetooth LE, reads its current state, lets the user tune firing/timing profiles, and writes changes back to the board.

Targets:
- **Android** — primary platform.
- **Desktop (JVM)** — macOS/Windows/Linux via Nucleus packaging; Bluetooth through BlueFalcon with a runtime engine chosen per OS.
- **Wear OS** — watch app (`:app:wear`). It connects to a board directly over BLE, edits profiles (numeric fields, fire mode, eye mode) and the board language, brightness and screen-off delay, and syncs the known devices with the phone in both directions over the Wearable Data Layer (see Wear OS Sync).
- **Browser (Wasm)** — `:app:browser` is a wasmJs Compose target. Bluetooth goes through BlueFalcon's Web Bluetooth engine `JsEngine` (`platform/browser/bluetooth`). In the 3D viewer of the board, the app runs in a same-origin iframe, and the viewer puts the bridge `window.snapshotBoard` (version 1) on its own window. There the engine is `EmulatorEngine` (`bluetooth/emulator`), which talks to the WebAssembly model of the board firmware through that bridge and needs no Web Bluetooth. `EmulatorEngine` copies the behaviour of `JsEngine` 3.7.10, so a defect on the web also shows in the viewer; its KDoc gives the few differences. When you update BlueFalcon, compare `EmulatorEngine` with the new `JsEngine` again. With `JsEngine` only, `PermittedBoards` gives the engine the boards of `navigator.bluetooth.getDevices()` at start, so a known board connects after a reload with no chooser. `JsEngine` has no API for this, so a one-time `requestDevice` offer on `navigator.bluetooth` passes each device through `scan()`; `PermittedBoardsTest` runs the real `JsEngine` and fails if a BlueFalcon update breaks this. `EmulatorEngine` is not the simulated board: it is in the release web bundle on purpose, because the viewer shows the bundle of `:app:browser:wasmJsBrowserDistribution` (see CI for how the website gets it). The app shows `UnsupportedBrowserScreen` when the browser has no `navigator.bluetooth` and the page has no viewer bridge. There is no media module on the web. A device photo comes from `<input type="file">` (`pickWebPhoto`, `core:common`), goes to IndexedDB (`WebPhotoStore`, `core:store`), and loads in Coil through `WebPhotoFetcher`, which `main()` installs.
- **Zepp OS** — separate JavaScript mini-program for Amazfit watches (`app/zepp`, not part of the Gradle build).
- **iOS** — iPhone + iPad, reusing the shared Compose UI. `:app:ios` is a KMP module producing the `ComposeApp` framework (DI assembly + `MainViewController` hosting `core:common` `App()`); the Xcode shell is in `iosApp/`. The `core:*` modules and `platform:ios:{bluetooth,di}` target `iosArm64`/`iosSimulatorArm64` (Bluetooth via BlueFalcon `IosEngine`). Builds for the simulator and runs on a physical device on a Mac with Xcode — signing is configured (automatic, `DEVELOPMENT_TEAM` set in the Xcode project), and the app installs and launches on an iPhone. On-device BLE/camera are still pending validation.

## What the App Does (Features)

- **Device discovery & connection** — scan for nearby boards over BLE, save known devices, reconnect. Connection runs a 4-step progress flow (connect → discover services → read device info → sync profiles), surfaced in `ConnectingDeviceScreen`.
- **Device configuration** — edit device name, boot text (shown on the marker LCD), active profile, board menu language, screen brightness, screen-off delay and the display area, plus a read-only firing-lockout indicator (`ConfigureDeviceScreen`). The display area (`DisplayWindow`, `core:model`) is the part of the board's display that the grip frame shows; the user picks a `GripFrame` preset or edits the rectangle, and the app clamps it to the panel before a write, because the board refuses the whole message for a rectangle out of bounds. The E1 and E2 presets still hold placeholder rectangles, so their `GripFrame.measured` flag is false and the picker hides them (`GripFrame.offered`); a stored E1 or E2 rectangle shows as a custom one. Lockout is a board-owned fact with no app-writable field, so it is displayed, not editable. The app has no battery setting: it does not read `DeviceConfig.battery_type`, and a write sends the unset value, which leaves the board's own battery type alone (a concrete default could give a 2S LiPo board the thresholds of an alkaline cell). Language, brightness and screen-off delay are board settings the board can refuse: each is `null` until the board reports one, a `null` write sends the unset value and leaves the board's own alone, and the engine reads `DeviceConfig` back after a write to record what the board actually kept. `boardValuesMatch` (`core:model`) makes a `null` on either side match, so a missing value never raises an unsaved badge or a conflict no write could clear.
- **Firmware update** — `ConfigureDeviceScreen` has a Firmware section (`screen/firmware/FirmwareSection.kt`). It shows the board version, the board type, the newest release of the stable channel and its `warning`, and an Update button. The button is active only when `FirmwareStatus.canUpdate` is true; otherwise the section shows the text of the `FirmwareUnavailableReason`, and `CHECK_FAILED` adds a "Check Again" button for `refresh()`. During an update of the board, or with a result that is not dismissed, the button opens the update screen again. `ScreenKeyFirmwareUpdate(deviceId)` opens `FirmwareUpdateScreen`: the warnings and a confirm button (the call of `start`), then the steps of plan §2.7 with a progress bar for the download and the upload and the swap time, then the result with a retry button when `FirmwareUpdateFailure.canRetry` is true. A stop needs a confirmation, and the screen offers no stop during the reset. A back navigation does not stop the update, because the coordinator is not tied to the screen. The screen stays on (`Modifier.keepScreenOn()`) while the update runs. The phase rules are pure functions in `FirmwareUpdateState.kt`.
- **Firing profiles** — up to 4 `MarkerProfile`s per device, each with a name and 15 tunable fields: fire mode (`MECHANICAL`, `SEMI`, `TRIGGER_ONLY`, `FULL_AUTO`, `RAMPING_NXL_2026`, `CUSTOM_RAMPING`; there is no burst mode), eye mode, and 13 numeric fields (fire-rate cap, eye threshold/debounce, cocking-solenoid timeout, sear dwell, cocking delay/dwell open/dwell closed, trigger debounce on/off, and three ramping values). The numeric fields are described by the shared `MarkerProfileField` table (see Code Generation & Tooling). Two fire mode rules follow the board menu. The league sets the rules of `RAMPING_NXL_2026`, so the editors hide the fire-rate cap in that mode (`MarkerFireMode.hasFixedRateCap`), and the max balls-per-second uses the league cap of 10.5 BPS (`NXL_2026_RATE_CAP`). The three ramping values are the settings of `CUSTOM_RAMPING`, so the editors show them in that mode only (`MarkerFireMode.usesCustomRamping`). A hidden field keeps its stored value, and a write still sends it. The stored name of `RAMPING_NXL_2026` is `RAMPING_CUSTOM` (`@SerialName`), and the Zepp `FireMode` has the same stored value. Stored device files hold that name, so do not change it. Edited in `ProfileScreen` with a live **timing diagram** and a computed max balls-per-second.
- **Divergence / sync tracking** — local edits are diffed against the board's last-read `BoardState`; "unsaved" badges and a pending-changes banner show what differs. Changes require an active connection to write.
- **Conflict resolution** — when a reconnected board reports state that differs from the last-read `BoardState` and the app has local edits (`hasDiverged()`), the user resolves it (take device state vs. write local back to board) via `DeviceSyncDelegate` / sync dialogs. With no local edits the app takes the board state with no prompt, because it has nothing to lose. The Zepp `DeviceStore.onDeviceRead` uses the same rule.
- **Device photos** — per-device badge image from camera or gallery.
- **Responsive UI** — a screen shows two panes side by side only when its container is wider than it is tall and at least 600 dp wide (`fitsTwoPanes`, `core:common`). Every other container stacks one column. The width limit is necessary on the web: on a phone, the app can run in a frame that is 390 dp wide and less than 390 dp tall.

## Build Commands

```bash
# First-time setup: the protocol schemas are a git submodule
git submodule update --init --recursive

# Android debug build
./gradlew :app:android:assembleDebug

# Full build. CI does not run this: it runs a 5-target matrix (see the CI section). On a
# computer with many cores the full build can run out of memory; build one CI target at a time.
./gradlew build --no-daemon --no-parallel --stacktrace

# Run desktop app
./gradlew :app:desktop:run

# Run desktop app with Compose Hot Reload (desktop JVM only; :app:desktop applies the plugin)
./gradlew :app:desktop:hotRunJvm

# Wear OS app
./gradlew :app:wear:assembleDebug

# Android lint
./gradlew lint

# Zepp app (separate Node.js toolchain, run from app/zepp)
cd app/zepp && npm install && npm run build   # build-proto (pbjs) + zeus build
```

Tests run as part of `./gradlew build`. There is no separate test command. There is no Detekt/ktlint/Spotless — quality is enforced by Android `lint`, `allWarningsAsErrors` (build scripts only — see Key Conventions), the `:core:resources:localizationCheck` task (see Code Generation & Tooling), and the review agents below.

**After any change under `composite/src` or to `composite/{build,settings}.gradle.kts` or `composite/gradle.properties`, run `./gradlew -p composite clean test publish` and commit the regenerated `composite/m2` tree.** `settings.gradle.kts` resolves `composite` from `maven { url = uri("composite/m2") }`, not `includeBuild`, because `includeBuild` breaks the build cache that CI depends on. You can't forget the republish silently: `composite` publishes under a version carrying a **SHA-256 digest of its own source** (e.g. `1.0-f329313f803c`), and `settings.gradle.kts` recomputes that digest and demands exactly that version, so an unpublished edit fails in ~1s with `Plugin [id: 'composite', version: '1.0-…'] was not found`. Keep `buildLogicVersion()` identical in both files, apart from the indentation and the `java.security.` prefix (it can't be shared — one build produces the plugin the other consumes; a divergence fails the build immediately, and `BuildLogicVersionParityTest` in composite compares the two copies). The digest covers `composite/src/main`, `composite/build.gradle.kts`, `composite/gradle.properties` and `composite/settings.gradle.kts` — **not** `gradle/libs.versions.toml`, so a Kotlin/Gradle bump still needs a manual republish. `.gitattributes` keeps the files of the digest at LF: Git on Windows checks out CRLF by default, CRLF changes the digest, and the Windows leg of `build-release.yml` is the only Windows build. The Kotlin Gradle plugin is `compileOnly` in composite, so composite does not force its KGP version on the root build. Publishing clears `m2` first, so exactly one version is ever committed. This costs no cache hits: the version never enters the jar bytes, so downstream cache keys are unchanged.

## Module Structure

Authoritative list lives in `settings.gradle.kts`. `app/zepp` and `iosApp/` (the Xcode project) are NOT Gradle modules.

```
app/
  android/          - Android app entry (Application DI, MainActivity, permissions, RequestManager bus, WearSyncManager)
  android/baseline/ - Android Baseline Profile generator (separate module, builds with Java 21)
  browser/          - wasmJs web entry point (DI assembly, Web Bluetooth capability gate, UnsupportedBrowserScreen)
  desktop/          - JVM desktop entry point (Nucleus packaging + auto-updater)
  ios/              - iOS framework module (ComposeApp): assembles DI + hosts core:common App(); Xcode shell lives in iosApp/
  wear/             - Wear OS app (direct BLE, profile and board-setting editor, two-way phone sync)
  zepp/             - Zepp OS (Amazfit) JS mini-program — separate npm/Zeus build, not in settings.gradle.kts
core/
  common/           - Shared Compose UI, screens, navigation, ViewModels, delegates
  model/            - @Serializable domain models (Device, MarkerProfile, BoardState, Photo, AdvertisementData, ...)
  store/            - State & persistence (DeviceStore, StoreIOProvider; jvmMain has StoreIOProviderJvm)
  di/               - Kodein DI root (coreDiModule imports bluetooth/media/store/viewmodel submodules)
  bluetooth/        - BLE abstractions: RequestManager, StateManager, DeviceReadResultBus, OTS GATT client + protocol mappers
  camera/           - imagepickerkmp implementation of ImagePicker (camera + gallery launchers); Android, desktop and iOS only, so Wear and browser never pull CameraX
  debug-board/      - DebugBoard seam (+ inert NoDebugBoard); always on the classpath, both platforms
  fake-board/       - FakeBoard, the simulated board behind that seam; debug configurations only
  firmware/         - FirmwareFeed (firmware site, image download and check; Ktor behind FirmwareHttp) + FirmwareUpdateCoordinator
  media/            - Media permission types (MediaRequestManager + MediaStateManager); no shared screen reads them
  protocol/         - Protobuf protocol module (consumes generated Kotlin)
  protocol/builder/ - Protobuf codegen toolchain; schemas live in the Configuration-Protocol git submodule
  resources/        - strings.xml of every locale + shared Compose resources (generates public Res class)
  util/             - Utilities
  app-version/      - Generated AppVersion stamp (leaf module so per-commit churn can't invalidate core:common)
platform/
  android/bluetooth/ - Android BlueFalcon implementation
  android/media/     - Android gallery/photo picker implementation
  android/store/     - Android StoreIOProvider (Context file IO + ContentResolver)
  android/di/        - Android DI bindings (bluetoothModule, mediaModule)
  browser/bluetooth/ - Web Bluetooth BlueFalcon implementation (scan filters double as the GATT allowlist)
  browser/di/        - Browser DI bindings (bluetooth only)
  desktop/bluetooth/ - JVM BlueFalcon implementation (runtime engine selected by OS)
  desktop/di/        - JVM DI bindings (bluetooth only — desktop has no media module)
  ios/bluetooth/     - iOS BlueFalcon implementation (IosEngine)
  ios/di/            - iOS DI bindings (bluetooth only)
composite/          - Build-logic build published to composite/m2; helpers (configureOptIns, resolveProjectVersion, LocalizationCheckTask)
```

## Architecture

### Layered Dependency Flow
`app` → `core:di` → `core:common` → `core:model` / `core:store` / `core:bluetooth`
`core:di` binds the `core:media` managers. `core:common` does not use them: `imagepickerkmp` asks for the camera permission itself.
`core:di` depends on `core:common` (it binds the ViewModels), not the reverse. `core/*` modules must not import from `platform/*` or `app/*`. Shared code declares interfaces; platform code provides implementations, wired via Kodein at runtime.

### State Management
- `DeviceStore` (`core/store`) is the single source of truth: holds a `StateFlow<List<Device>>` and changes it with an atomic `MutableStateFlow.update` (compare-and-set), so a transform passed to `update {}` must have no side effects. It persists to JSON via the injected `StoreIOProvider`, serializes file IO with a coroutine `Mutex` (not `synchronized`), and runs IO on a dispatcher that defaults to the per-platform `storeIoDispatcher` (expect/actual; `Dispatchers.IO` on the JVM). Tests replace the dispatcher through the constructor. Callers that read `knownDevices` once must first `awaitLoaded()`, because the empty list before the disk load is not an empty store. The `devices.json` write is atomic (temporary file, then move), and a corrupt file is moved aside.
- `StoreIOProvider` is platform-specific: `StoreIOProviderJvm` (kotlinx-io `SystemFileSystem`) and `StoreIOProviderAndroid` (in `platform/android/store`, using `Context` file IO + `ContentResolver`), plus iOS (Application Support) and Wasm (`localStorage`, and IndexedDB for photos) providers. The JVM provider roots its files in a per-OS app-data directory, and moves a legacy `devices.json` from the working directory on first start. The Android impl is bound with `overrides = true` in `app/android/Application.kt`, not in the platform DI module.
- UI consumes state via `collectAsState()`.

### Delegate Pattern
ViewModels stay thin; logic lives in delegate interfaces with `Impl` classes that take stores/managers as constructor params (ViewModels inject the interface):
- `DeviceViewModelDelegate` — device update, photo storage, configuration, board writes.
- `DeviceSyncDelegate` — observes `DeviceReadResultBus`, surfaces `PendingSync` (`NewDevice` / `Conflict`), exposes confirm/dismiss/resolve.

### RequestManager + StateManager Pattern (Bluetooth & Media)
- **RequestManager** (`BluetoothRequestManager`, `MediaRequestManager`): shared-flow event buses for cross-layer side effects (enable Bluetooth, request permission, start/stop scan, connect, write device, update and confirm firmware). No shared code sends a `MediaRequest`; the photo picker asks for the camera permission itself. `MainActivity` monitors these and performs the real Android intents/permissions. Do not call Android APIs from shared code — route through a RequestManager.
- **StateManager** (`BluetoothStateManager`, `MediaStateManager`): expose `StateFlow` of capability/connection state (`NeedsPermission`/`Disabled`/`Ready`/`Scanning`, connected devices, 4-step `ConnectionProgress`, the `updateServices` of the connected boards, the `firmwareUpload` of the last firmware request). The apps set `MediaState`, but no screen reads it.

### Bluetooth & Protocol
- Abstractions in `core/bluetooth`; implementations in `platform/android/bluetooth` and `platform/desktop/bluetooth` (BlueFalcon). Desktop picks the engine at runtime by `os.name` (macOS → MacosJvm, Windows → Windows, Linux → Rpi).
- `core/bluetooth/.../ots/OtsClient.kt` is a **GATT-only** Object Transfer Service client (OACP/OLCP handshake, vendor "Object Data" characteristic for chunked transfer — not L2CAP CoC). `OtsModelMapper` converts protocol messages → domain models; `DeviceReadResultBus` decouples BLE reads from store persistence.
- The board sends **no notification** when the user edits it in its menu. Its only notifications are the OTS control-point responses, the object data and the log lines (`observeForDebug` only logs them). So before each write, `BluetoothConnectionEngine` reads `profiles` and `device_config` again and compares the fields that the app edits with its last read (`showsABoardEdit`). For a difference, it does not write: it sends the board state on `DeviceReadResultBus` (the sync rules then raise the conflict) and reports `BoardChangedBeforeWriteException`. After a write, it records what the board reads back, so its own write does not look like a menu edit.

### Firmware Feed
`core:firmware` reads the firmware site that the `Firmware` repository publishes on GitHub Pages (`FIRMWARE_SITE_URL`; the constructor of `FirmwareFeed` takes another URL for a local site). `FirmwareFeed.latest()` reads `<board>/latest.json` for the stable channel and `<board>/index.json` for the beta channel. `download()` gives an image only when its size and its SHA-256 agree with the site, and it keeps the last good image in memory for a second attempt. The JSON types are in `core:model` (`FirmwareManifest`, `FirmwareBoardIndex`, `FirmwareSiteIndex`), and they must parse the output of `scripts/pages_index.py` in the `Firmware` repository. The feed ignores unknown keys and refuses a newer `schema`. The fixtures in `core/firmware/src/jvmTest/resources/pages` are copies of the generator fixtures, so copy them again when the generator changes.

The offer rule is `FirmwareManifest.isOfferFor()`: the `compatible_hardware` of the release must hold the board of the `HardwareTarget` (the answer of `os info -f i`), and the release must be newer than the `FirmwareVersion` of the board. Ktor is in `core:firmware` only, behind `FirmwareHttp`. `core:di` binds `FirmwareFeed`, and each app binds the engine: CIO (`platform:desktop:di`), Darwin (`platform:ios:di`), Js (`platform:browser:di`) and Android (`app:android`). The Android binding is not in `platform:android:di`, because the Wear app shares that module and does not update firmware. The OkHttp engine of Ktor 3.6.0 needs `compileSdk` 37, so Android uses the `HttpURLConnection` engine, and only the Android app has the `INTERNET` permission. `firmwareModule` (`core:di`) binds the feed and the coordinator. `coreDiModule` does not import it: the Android, desktop, iOS and browser apps import it, and the Wear app does not, because Wear binds no `FirmwareHttp`. A ViewModel that injects the coordinator must therefore not be bound in `viewModelModule`, which Wear imports too. `firmwareModule` binds `FirmwareSectionViewModel` and `FirmwareUpdateViewModel` for this reason. Each app that imports `firmwareModule` also binds a `FirmwareUpdatePolicy` and a `BluetoothDeviceStateSource` (the scan flow of its `BluetoothManager`, wrapped by `DebugBoard.deviceState` on Android and iOS).

**Board target after connect.** After the 4-step connect, and not as a fifth step, `BluetoothConnectionEngine` reads the update service when the discovered services have the SMP service. The read runs on the engine scope after the connect releases its locks, so a Save does not wait for it; no firmware request can use the session until it ends. It opens a `BoardFirmwareSession`, reads `os info -f i`, `os mcumgr_params` and `image list`, and records a `BoardUpdateService` (status, `HardwareTarget`, buffer size, running image) in `BluetoothStateManager.updateServices` by board id. The entry is in memory only, so `DEVICE_SYNC_SCHEMA_VERSION` does not change. No SMP service (`ABSENT`), an engine with no typed write (`UNSUPPORTED_PLATFORM`: macOS desktop, web) and an SMP error or time-out (`FAILED`) never fail the connection; the next connect reads again. The web app gets the SMP service through its GATT allowlist and gives `UNSUPPORTED_PLATFORM`; the model of the board in the viewer has no SMP service and gives `ABSENT`. The teardown closes the session and removes the entry.

**Firmware requests.** `BluetoothRequest.UpdateFirmware(deviceId, image, requestId)` runs `image list`; when slot 1 already holds the image (the MCUboot hash in the TLVs of the file, `McubootImage.hashOf`), it skips the upload, and a pending slot also skips `image test`. Else it runs the upload with the buffer size of the board, `image list` for the hash of slot 1, `image test` and `reset`, at high connection priority. It holds `writeMutex` for the whole upload, so no OTS write and no `showsABoardEdit` read runs while the board holds fire control. `BluetoothRequest.ConfirmFirmware(deviceId, requestId)` confirms the running image when `image list` shows it as not confirmed. Both handlers return at once, and the engine reports each step in `BluetoothStateManager.firmwareUpload` with the `requestId`. A `Disconnect` stops an upload.

**Coordinator.** `FirmwareUpdateCoordinator` (interface, `FirmwareUpdateCoordinatorImpl`) runs the flow of plan §2.7: check, download, prepare, upload, reset, the swap wait (`EXPECTED_SWAP_MILLIS`, 30 s: the bench measured about 18 s on a devkit; a `snapshot_v2` board is not measured yet), verify and confirm. A screen uses only its API: `status(deviceId)` (board version, target, latest release, `canUpdate`, `FirmwareUnavailableReason`), `progress`, `start`, `retry`, `cancel`, `dismiss`. The verify step connects again and compares the hash of the running image with the uploaded hash; another image is `REVERTED`, and no connect in `VERIFY_LIMIT_MILLIS` is `NO_ADVERTISEMENT`. A retry after the reset only verifies again. The board version comes from the advertisement on Android and from `image list` on the other platforms, whose engines give no advertisement data.

**Release gate.** `FirmwareUpdatePolicy.UPDATES_RELEASED` is false until a firmware release has the image signature and swap using move (plan decision 2). A release build then shows the status with `NOT_YET_RELEASED` and offers no update. The debug source sets of Android and iOS bind `FirmwareUpdatePolicy(debugBuild = true)`, next to the `DebugBoard` binding. The desktop app has no debug build: `--firmware-site-url <url>` (or `-Dsnapshotpb.firmwareSiteUrl=<url>`) points the feed to a local site and opens the gate for a bench test.

### Simulated Board (running without hardware)
`core:debug-board` holds the `DebugBoard` seam — it wraps the scan flow, claims the Bluetooth
requests it answers, and reports whether it is active. The simulated board reports the target
`snapshot_v2/esp32c3`, answers `UpdateFirmware` with a fake upload and a fake reset, and advertises
the new version after a fake swap of 5 s. `fakeBoardModule` also binds a `FirmwareFeedOverride`
that gives the in-memory `SimulatedFirmwareSite` while the board is active, so the Demo entry shows
the whole update with no hardware and no network. `core:fake-board` holds `FakeBoard`, which
implements that seam with no radio at all: it walks the same 4-step connect, serves a board named
`Snapshot PB`, and answers reads and writes. **Neither the simulated board nor the code that binds
it reaches a release binary**, and that is a compile-time guarantee on both platforms: Android
depends on `core:fake-board` through `debugImplementation`, and `app:ios` through a source set
(`src/iosDebug` vs `src/iosRelease`) chosen by the `simulatedBoard` flag in its build script, which
reads Xcode's `CONFIGURATION`.

Turning it on is per-launch, never persisted, and the entry point decides:
- **Android** — the Demo launcher entry (`DemoActivity`) starts `MainActivity` with `EXTRA_FAKE_BOARD`.
  The normal entry turns it off. Note that a debug build therefore exposes **two** `LAUNCHER`
  activities, so `getLaunchIntentForPackage` is ambiguous — name the activity outright when you
  mean one of them (see `ConnectivityE2ETest`).
- **iOS** — the `--fake-board` launch argument, read in `MainViewController.startOnce()`. There is no
  second app icon on iOS, so the switch rides the launch. The iOS Simulator has no CoreBluetooth at
  all, which is what makes this the only way to drive the app there.

### Wear OS Sync (two-way)
The phone and the watch each push their own edits and accept the other's over the Wearable Data Layer:
- Phone: `WearSyncManager` (`app/android`) observes `DeviceStore` and puts each device (with a scaled photo asset) at `/devices/<id>`; `PhoneDeviceSyncService` receives the watch's items.
- Watch: `WearWatchSyncManager` (`app/wear`) puts the watch's edits at `/devices/<id>`; `WearDeviceSyncService` receives the phone's items.
- The newer `lastModified` wins. A delete on either side removes the item and writes a `/deleted/<id>` marker that the other side acts on; a device that comes back clears the marker.
- Both sides wait for `DeviceStore.awaitLoaded()` before they compare, and skip a push when nothing synced changed.
- The DataMap carries a `schema_version` (`DEVICE_SYNC_SCHEMA_VERSION`), so that a peer on an older build does not write default values back over fields it does not know.

Read the KDoc of `DeviceSync.kt` (`core:model`) and of `WearSyncManager` before you change the sync rules. A change to the synced fields needs a `schema_version` bump, and `boardState` is a synced field.

### Navigation
androidx.navigation3 with type-safe screen-key data classes (`ScreenKeyDeviceBrowser`, `ScreenKeyProfiles`, `ScreenKeyProfile`, `ScreenKeyProfileAdd`, `ScreenKeyDeviceConfigure`, `ScreenKeyConnectingDevice`, `ScreenKeyFirmwareUpdate`, ...). `NavigationController` wraps `NavBackStack`.

### Dependency Injection
Kodein. `coreDiModule` (`core:di`) is the root, importing bluetooth/media/store/viewmodel submodules. `platform/android/di` adds Android bluetooth + media bindings; `platform/desktop/di` adds bluetooth only. App-level modules add screen ViewModels and the `StoreIOProvider`/`WearSyncManager` bindings. New capability: define interface in `core`, bind in `core/di`, provide platform impl in `platform/*`.

### Serialization
- JSON (kotlinx.serialization) for device persistence; all models are `@Serializable` data classes in `core/model`.
- Protobuf (kotlinx-serialization-protobuf) for the board protocol.

## Code Generation & Tooling

- **Protobuf**: schemas live in the `core/protocol/builder/Configuration-Protocol` git submodule. `core:protocol:builder` runs `kotlinx-protobuf-gen` (via the Google Protobuf Gradle plugin) to produce Kotlin serializers consumed by `core:protocol`. The same `.proto` files feed the Zepp app's `pbjs` codegen.
- **Compose Resources**: `core:resources` generates a public `Res` class. The English strings are in `core/resources/src/commonMain/composeResources/values/strings.xml`, and each translation is in a `values-*` directory beside it.
- **`copyStringRes` workaround**: `app/android` copies Compose-Resources strings into Android `res/values/` before build (via a `preBuild`-dependent `Copy` task) so Android `getString()` works. In `copiedRes/values/strings.xml`, escape apostrophes as `\'` — bare `'` and `&apos;` both fail AAPT2.
- **Localization check**: `:core:resources:localizationCheck` (`composite`'s `LocalizationCheckTask`, hooked into `check` and named on the `desktop` CI target) compares every `values-*/strings.xml` with the base locale. It **fails** the build on a format-specifier disagreement, a repeated `name`, or an unescaped apostrophe in the base locale; it only **warns** on an absent key, an unknown key, an absent locale, or a value still in English, so a new English string doesn't block the build before its translation lands. Those warnings are a to-do list, not noise — **translations are written by hand in this repo**, and the repo is their only source. One summary line per locale goes to the console; per-string detail goes to `core/resources/build/reports/localization/strings.txt`. Exceptions live in `untranslatedAllowList` in `core/resources/build.gradle.kts`: a bare name covers every locale (brand names, `Semi`, `Ramping`, `Sear`), while `<locale>:<name>` covers one language for a word that is genuinely spelled the same there (`values-es:common_no` — German writes `Nein`, so a bare entry would hide a real gap).
- **Shared profile field table**: `MarkerProfileField` (`core:model`) holds the unit, the limits, the editor group and the visibility rule of each numeric profile field. The Wear stepper editor uses it directly. The Zepp app keeps a hand-written mirror with the same entry names in `app/zepp/src/utils/profileFields.js`. `core/model/src/jvmTest/resources/profile-fields.json` is the parity fixture that both sides are tested against, so a change to the table needs the same change in the fixture and in the Zepp mirror. Each entry has `affectsMaxBps`, which tells the watch steppers which fields move the max balls-per-second. A second fixture, `marker-profile-fields.json`, holds every `MarkerProfile` field and its default. Its `appOnly` group holds the fields that only the Kotlin app keeps (`sourceSlot`); the divergence checks ignore them, so the Zepp lists do not hold them. `MarkerProfileFixtureTest` tests it against `MarkerProfile`, and the Zepp parity test tests it against `PROFILE_REQUIRED_FIELDS` and `PROFILE_FIELD_DEFAULTS` in `OtsModelMapper.js`. The Zepp divergence check and default fill come from these two lists, so a new `MarkerProfile` field needs the same change in the fixture and in the Zepp lists.
- **Baseline Profiles**: `app/android/baseline` generates them via instrumented benchmark tests (Java 21); merged into the release APK.
- **composite build**: convention helpers including `configureOptIns()`.

## Key Conventions

- Experimental APIs are opted in centrally via `configureOptIns()` in the `composite` build (`ExperimentalUnsignedTypes`, `ExperimentalUuidApi`, `ExperimentalSerializationApi`) — do not scatter `@OptIn` at call sites. Every module that compiles Kotlin calls `configureOptIns()` in its `kotlin {}` block; `core:protocol:builder` compiles no Kotlin, and a comment in its build file says so. A module that has no kotlinx-serialization on its compile classpath calls `configureOptIns(serialization = false)`, because the compiler warns about an opt-in marker that it cannot find.
- `org.gradle.kotlin.dsl.allWarningsAsErrors = true` (in `gradle.properties` and `composite/gradle.properties`) — this governs **`.gradle.kts` script compilation only**, so any warning in build scripts fails the build. Keep `.gradle.kts` clean. It does **not** apply to project Kotlin sources: nothing sets `-Werror` or `compilerOptions.allWarningsAsErrors` for `src/`, and the Android build currently logs several deprecation warnings without failing. Still fix warnings in project sources (unused imports/vars, redundant casts, deprecations, non-exhaustive `when`) — it's convention here, just not compiler-enforced.
- `commonMain` must stay platform-agnostic — no `android.*`/`java.*`-only imports in shared source sets.
- Adding a non-nullable field without a default to a persisted `@Serializable` model breaks deserialization of stored JSON — give new fields defaults or make them nullable.
- Logging uses Arbor: `private val log = Arbor.tag("ClassName")`, then `log.d {}`/`log.e {}` (lambda form). No `println`/`android.util.Log`.
- New strings go in `core/resources`, not hardcoded. Add the English string to `values/strings.xml` **and** the translation to every `values-*` locale in the same change — `localizationCheck` warns for each locale you leave behind.
- Version catalog: `gradle/libs.versions.toml`. Android `compileSdk`/`targetSdk` 36, `minSdk` 24 (Wear overrides to 30). Android/Wear compile to JVM 11.
- Key libraries: Compose Multiplatform, Kodein (DI), BlueFalcon (BLE), Arbor (logging), Coil (images), Nucleus (desktop packaging), kotlinx serialization/coroutines/datetime, androidx.navigation3, play-services-wearable, Ktor client and kotlincrypto `sha2` (`core:firmware` only).

## Claude Code Tooling (`.claude/`)

- **Subagents** (`.claude/agents/`):
  - KMP app: `kmp-architect` (architecture/where-things-go), `kmp-feature-scaffolder` (new screens/ViewModels), `bluetooth-protocol-specialist` (BLE/protocol), `gradle-build-doctor` (build/codegen), `kotlin-code-quality-reviewer` (code-quality review of Kotlin changes).
  - Zepp app (`app/zepp/`): `zepp-architecture-specialist` (Zepp app design/structure, BLE/OTS, protocol pipeline, state flow), `zepp-js-code-quality-reviewer` (JS code-quality review of Zepp changes).
- **Auto-consult hooks**: `PostToolUse` hooks in `.claude/settings.json` fire after `Edit`/`Write`/`MultiEdit` and remind Claude to consult the relevant agents before treating a change as complete:
  - `.kt`/`.kts` files → `kmp-architect` + `kotlin-code-quality-reviewer`.
  - `app/zepp/**/*.js` files (excluding generated `proto.js`) → `zepp-architecture-specialist` + `zepp-js-code-quality-reviewer`.

## CI

GitHub Actions (`.github/workflows/`):
- `validation.yml` — on PRs to `master` and on a push to `master` (ignoring `app/zepp/**`), runs a **5-target matrix**, each on its own runner: `android`, `desktop`, `browser`, `ios`, `macos`. Only `macos` runs on `macos-latest`; the workflow selects that runner from the target name, because a new matrix value changes the Gradle cache key of every target. Each target names its own task list. Java 21 (the JetBrains JDK, which `setup-java` installs), accepts Android SDK licenses. `gradle/gradle-daemon-jvm.properties` makes the Gradle daemon run on a JetBrains JDK 21, and Nucleus makes the runtime of the desktop installers from the JDK of the daemon. With a different JDK on the runner, Gradle downloads the JetBrains JDK through the foojay API, and only the Gradle cache hides a failure of that download. The download URLs in that file are dead (foojay had no JetBrains JDK 21 on 2026-10-08, so `updateDaemonJvm` could not make new ones); a computer with no JetBrains JDK 21 needs one installed by hand until foojay has it again. A build scan is published only for a failed build on CI (the Develocity plugin in `settings.gradle.kts` decides; do not add `--scan`), because the scans server is public and this repo is private. A failed job uploads its test reports, test results and lint reports as the artifact `reports-<target>` (kept 14 days), because the console gives only the name of a failed test; a browser test that Mocha stops after its time limit (2 seconds by default, 30 seconds in `core:common` through `core/common/karma.config.d/mocha-timeout.js`) shows as the bare text `Error`. The push-to-master run writes the Gradle caches (`cache-read-only` is false only on `master`); a PR run only reads them, and only a PR run cancels an older run of the same PR. A separate `scripts` job runs `python3 -m unittest discover -s scripts/tests` and `python3 -m unittest discover -s tools/ble-validation/tests` when a PR changes `scripts/` or `tools/ble-validation/`. The `android` target assembles the debug and release APKs of both apps, compile-checks the baseline module and the instrumented tests of both apps (`compileDebugAndroidTestKotlin`), and runs `lint`; it does not run `:app:android:build`, because that assembles the three baseline-profile variants (`benchmark`, `nonMinifiedRelease`, `benchmarkRelease`) and runs R8 three times for a profile no PR job can generate. Both apps set `lint.checkReleaseBuilds = false` for the same reason: the full `lint` already covers the sources. The repo is public, so the standard Linux runner has 4 CPUs (2 cores with 2 threads each) and 15.6 GiB RAM (measured on 2026-10-11); a private repo gets 2 CPUs and 7.8 GiB from the same label. The standard macOS runner has 3 CPUs and 7 GB RAM (GitHub documentation). Gradle runs four tasks at the same time on the Linux runner, and each task that a target does not need still costs time. The `ios` target only compiles, because a Linux host cannot link an Apple framework or run an iOS test. The `macos` target does that part: it links the debug `ComposeApp` framework for the device and for the simulator and runs `iosSimulatorArm64Test`. It runs Gradle with `--max-workers=1`. A Kotlin/Native link runs in the Gradle daemon, and on its first use of a library it makes the compiler cache of that library in `~/.konan`; the largest one is `material-icons-extended`, which `imagepickerkmp` brings in. One such link holds about 1.5 GB of the 3 GB Gradle heap, so the three links that three workers start at the same time fail with `Java heap space`. With an empty `~/.konan`, the task list took 8 minutes and 8.8 GB RAM on a MacBook, and 23 minutes on the runner; with a full one, 82 seconds and 3.1 GB on the MacBook. It does not link the release framework, and it must not run `:app:ios:build`: the release link of `iosArm64` fails with `Java heap space` in the 3 GB Gradle heap of `gradle.properties`, and with a 5 GB heap it used about 13 GB RAM on a MacBook. The first release link is thus still the `publish-ios` job of `build-release.yml`. **There is no root `build` and no unqualified `check` task.** A project's `build` depends on that project's own `check` only, so a task hooked into `:some:module:check` will NOT run in CI unless the matrix names it — `:core:resources:localizationCheck` is named on the `desktop` target for exactly this reason. The `desktop` target also runs `./gradlew -p composite test --no-daemon`, since `composite` is a separate build no root task reaches (and its JVM arguments differ, so a daemon would stay in memory beside the root one).
- `release.yml` cuts a calendar-version tag (only from a commit on `master`) and dispatches `build-release.yml`, which builds every target and publishes the desktop installers, the web bundle and the unsigned Zepp `.zab` to a GitHub Release, the AABs to Play, and the iOS archive to App Store Connect. The website does not build the app: the deploy workflow of the `snapshotpb.com` repository downloads `snapshotpb-web-<version>.tar.gz` from the Release of the tag in its `APP_VERSION`. So a change to the web app reaches the website only after a new release and a new `APP_VERSION` there, and the name of that asset must stay the same. The jobs that read signing secrets declare `environment: release`; the maintainer still has to move the secrets from the repository into that environment. The Android upload keystore is different: it is in the repo as the GPG-encrypted file `signing/upload.keystore.gpg`, the `android` job decrypts it before the build, and its passphrase and passwords are organization secrets (§7 and §8 of `docs/versioning-and-release-plan.md`).
- Workflow security rules: the workflow token is `contents: read` and only the job that needs a write scope gets it; build checkouts use `persist-credentials: false`; every third-party action (not under `actions/`) is pinned to a full commit SHA with the tag in a trailing comment, and `.github/dependabot.yml` updates the pins.
- `zepp-validation.yml` — builds the Zepp app (Node.js 22, `npm run build`) on changes under `app/zepp/**`.
