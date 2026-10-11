![Snapshot PB logo](https://github.com/SnapshotPB/Configuration-App/blob/master/art/snapshot-full-logo-256.png?raw=true)

# Snapshot PB Configuration App
This application provides configuration of the Snapshot PB paintball board for Autococker e-framed devices.

## Platforms

- **Android** — primary, fully supported.
- **Wear OS** — watch app targeting Wear OS 3+ (Pixel Watch). It connects to the board directly over BLE, edits profiles and board settings, and syncs the known devices with the phone in both directions.
- **Zepp OS** — watch app targeting Amazfit GTR 4 and T-Rex 3 devices, built as a JavaScript "Mini Program" using GATT-only OTS.
- **Desktop (JVM)** — runs on macOS, Windows, and Linux. Bluetooth is wired up via [BlueFalcon](https://github.com/Reedyuk/blue-falcon) but support is still limited compared to Android.
- **Browser (Wasm)** — Compose for Wasm build that uses Web Bluetooth. It shows an "unsupported browser" screen when the browser has no Web Bluetooth.
- **iOS** — iPhone and iPad, with the shared Compose UI. The `:app:ios` module builds the `ComposeApp` framework, and the Xcode project is in `iosApp/`. The app installs and starts on a device. On-device BLE is not validated yet.

## Building the app

Android can be built locally for side loading with the Android SDK tools on your system path or through [Android Studio](https://developer.android.com/studio).
```bash
./gradlew :app:android:assembleDebug
```

Desktop can be built and run directly, or packaged into a native installer (`.dmg` and `.zip` on macOS, `.msi` on Windows, `.AppImage` on Linux) via the Nucleus framework tasks.
```bash
./gradlew :app:desktop:run
```

Zepp OS can be compiled using the Zeus CLI:
```bash
cd app/zepp
npm install
npm run build
```

CI (`.github/workflows/validation.yml`) does not run `./gradlew build`. It runs a matrix of four targets, each on its own runner, and each target has its own task list. To do the same checks locally, run the task list of each target:
```bash
# android
./gradlew :app:android:assembleDebug :app:android:assembleRelease \
  :app:android:baseline:compileBenchmarkReleaseKotlin :app:android:compileDebugAndroidTestKotlin \
  :app:wear:assembleDebug :app:wear:assembleRelease :app:wear:compileDebugAndroidTestKotlin lint
# desktop
./gradlew :app:desktop:build jvmTest :core:resources:localizationCheck
./gradlew -p composite test
# browser
./gradlew :app:browser:build wasmJsBrowserTest
# ios (a Linux host cannot link or test the iOS framework)
./gradlew :app:ios:build
# release scripts
python3 -m unittest discover -s scripts/tests
```
Run one target at a time. On a computer with many cores, the full `./gradlew build` can use too much memory.

## Module Structure

The project is a multi-module Kotlin Multiplatform monorepo. Application entry points are thin wrappers around shared code:

```
app/
  android/          Android app entry point (MainActivity, permissions, Bluetooth bus, Wear sync)
    baseline/       Android Baseline Profile generator module
  browser/          Compose for Wasm entry point (Web Bluetooth check, DI assembly)
  desktop/          JVM desktop app entry point (uses the Nucleus application framework and auto-updater)
  ios/              iOS framework module (ComposeApp: DI assembly and the shared App()); the Xcode project is in iosApp/
  wear/             Wear OS watch entry point (Wear UI, profile editor, permissions, two-way sync)
  zepp/             Zepp OS companion watch app (JavaScript, BLE scanning, OTS client, local storage)
core/
  common/           Shared Compose UI, screens, navigation, ViewModels
  model/            @Serializable data models
  store/            State management & JSON persistence
  di/               Kodein DI wiring
  bluetooth/        Bluetooth abstractions (RequestManager pattern)
  media/            Media/gallery abstractions
  protocol/         Protobuf board communication protocol definition module
    builder/        Protobuf compiler toolchain that builds Kotlin protobuf models from schemas
  resources/        Crowdin-managed string resources
  util/             Utilities
  app-version/      Generated AppVersion stamp
platform/
  android/
    bluetooth/      Android Blue Falcon Bluetooth implementation
    di/             Android DI overrides
    media/          Android photo picker implementation
    store/          Android store IO provider implementation
  browser/
    bluetooth/      Web Bluetooth Blue Falcon implementation
    di/             Browser DI overrides
  desktop/
    bluetooth/      JVM Desktop Blue Falcon Bluetooth implementation
    di/             JVM Desktop DI overrides
  ios/
    bluetooth/      iOS Blue Falcon Bluetooth implementation (IosEngine)
    di/             iOS DI overrides
composite/          Build logic, published to composite/m2 (opt-ins, version resolution, localization check)
```

See [CLAUDE.md](CLAUDE.md) for more detail on architectural patterns (delegate pattern, RequestManager, navigation, DI).

## Desktop Bluetooth Setup & TODOs

Bluetooth support on the desktop platforms requires different OS-specific configurations and runtime setups:

### 🐧 Linux
- The desktop app uses the BlueFalcon `RpiEngine` for Linux.
- It requires **BlueZ** and **D-Bus** system bus access.
- Ensure the user is in the `bluetooth` group to avoid D-Bus `AccessDenied` errors:
  ```bash
  sudo usermod -aG bluetooth $USER
  ```
  *(A logout or system reboot is required for group additions to take effect).*

### 🍏 macOS
- Uses the native CoreBluetooth stack via BlueFalcon's `MacosJvmEngine`.
- Works out of the box with no extra dynamic libraries or permissions needed on standard JVM runtimes.

### 🪟 Windows
- Uses the `WindowsEngine` to communicate with the WinRT BLE APIs.
- **TODO List / Dependency Requirement:**
  - [ ] Package and bundle the native `bluefalcon-windows.dll` binary with the application.
  - [ ] Place `bluefalcon-windows.dll` inside the Java library path (e.g., system directory or using the `java.library.path` system property) or within the desktop resources directory (`library/src/windowsMain/resources/`) so BlueFalcon can locate it at runtime.

## Contributing

PRs and feature requests are welcome. Please open an issue first to describe the defect or feature before opening a PR so we can provide feedback if necessary. CI runs the four-target matrix of `.github/workflows/validation.yml` on every PR.

## License

The source code is licensed under the **GNU General Public License, version 3** — see [LICENSE](LICENSE).

**The Snapshot PB name and logos are not covered by that license.** "Snapshot PB", the Snapshot PB logo, and the application/launcher icons are trademarks of Snapshot PB (™) and are excluded from the GPL grant under GPLv3 §7(e). You may build, modify, and redistribute the code, but you may not ship it under the Snapshot PB brand — forks must replace the logo and app icons with their own. See [TRADEMARKS.md](TRADEMARKS.md) for the full trademark notice.
