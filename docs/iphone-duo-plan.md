# iPhone Duo support on iOS

This document is the plan to make the iOS app work correctly on iPhone Duo, the foldable
iPhone of Apple. It gives the facts about the device, the state of the app today, the work
items in order, the decisions for the owner, and the risks. The research date is
2026-11-18.

## 0. Goals

1. The app fills the full screen on the outer display and on the inner display. It does not
   run in the compatibility mode of an old SDK.
2. A fold or an unfold does not stop the app. The screen, the unsaved edits, the Bluetooth
   connection and a firmware update continue.
3. Each screen uses a layout that is correct for the size of each display.
4. A test makes sure that goals 2 and 3 stay true.

These are not goals: the hinge API, the reserved regions for custom bars, more than one
window, and the camera of the outer display. Section 6 tells why.

## 1. Facts about iPhone Duo

| Fact | Value | Source |
|---|---|---|
| Announcement and sale | Announced 2026-09-09, on sale 2026-10-23, with iOS 27.1 | 9to5Mac; third-party blogs |
| Size classes, outer display | Compact width and regular height in portrait; compact width and compact height in landscape. This is the same as other iPhone models | Apple tech talk 111461 |
| Size classes, inner display | Regular width and regular height | Apple tech talk 111461 |
| Width of the displays | Outer about 466 pt, inner about 669 pt. A third party measured these values, and Apple does not give them | GitHub issue Librechat-Mobile #602 |
| Fold and unfold | The app stays in the same process and changes its size. Apple does not say if this is a scene resize or a trait change | Apple tech talk 111461 |
| Orientation, outer display | It obeys the supported interface orientations of the app | Apple tech talk 111461 |
| Orientation, inner display | It does not obey the supported interface orientations. Use the size classes, not the orientation, for the layout | Apple tech talk 111461 |
| `UIRequiresFullScreen` | iPhone Duo obeys it, but the app still changes its size at a fold | Apple tech talk 111461 |
| Old SDK (iOS 26 or earlier) | The app shows in a smaller area with a black border. It does not go under the status bar and the camera | Apple tech talk 111461; 9to5Mac |
| iOS 27.0 SDK | The app goes to the left of the status bar on the inner display, but some areas stay blank | Apple tech talk 111461; 9to5Mac |
| iOS 27.1 SDK | The app goes to the edge of the screen. The standard navigation buttons and toolbar buttons go below the status bar in a vertical line | Apple tech talk 111461 |
| New APIs (iOS 27.1) | `UIViewReservedRegion` (UIKit) and `ReservedRegion` (SwiftUI) let custom UI use the area near the system UI | Apple tech talk 111461 |
| `UIScreen.main` | Apple will deprecate it. Use `window?.windowScene?.screen` and `traitCollection.displayScale` | Apple tech talk 111461 |
| Tools | Xcode 27.1 and the iOS 27.1 SDK. The Device Hub of Xcode has the iPhone Duo simulator, with controls to open, close and rotate the device | Apple tech talk 111461 |
| Simulator runtime | One source says "iOS 27.2 – iPhone Duo", a separate runtime in Xcode > Settings > Components. A different source says the iOS 27.1 runtime has it. Not confirmed | Showroom README; Librechat-Mobile #602 |

Apple's documentation is "Preparing your app for iPhone Duo"
(`developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo`).
The companion tech talks are 111462, 111463 and 111464. Read them before step 3.

## 2. Facts about Compose Multiplatform and Kotlin

| Fact | Value | Source |
|---|---|---|
| Crash at a fold or an unfold | CMP 1.12.x stops with `IllegalStateException: LayoutNode should be attached to an owner` approximately 3 s after the screen change, in both directions. The stack goes through `InsetsPaddingModifierNode` | Librechat-Mobile #602 |
| Cause | UIKit starts a synchronous layout pass during a Compose update, and Compose measures a node that is not attached. This is the analysis of the issue author | Librechat-Mobile #602 |
| Fix | compose-multiplatform-core PR #3491, "Do not run measure and layout during recomposition", merged 2026-10-11 into `release/1.13`. It is not in 1.12.x or in 1.13.0-alpha02 | Librechat-Mobile #602 |
| First version with the fix | CMP 1.13.0-beta01. beta01 and beta02 are on Maven Central (comment of 2026-11-10). The issue author estimates the stable 1.13.0 for mid-December | Librechat-Mobile #602 |
| Full iPhone Duo support | JetBrains meta-issue CMP-10776, target 1.14.0. The transition from the outer display to the inner display is open in that issue | Librechat-Mobile #602 |
| Kotlin/Native bindings | The `platform.UIKit` bindings of Kotlin 2.4.21 come from the iOS 26.4 SDK. They do not have the iOS 27.1 APIs | Showroom README |
| Shared code | Size classes, insets, Navigation 3, back navigation and state continuity at a fold work in `commonMain` with no iOS code. `material3-adaptive` does not know the fold posture on iOS | Showroom README |

The showroom uses CMP 1.12.2 and does not report the crash. Librechat-Mobile #602 reports the
crash on 1.12.2. Step 1 finds which statement is correct for this app.

## 3. State of the app today

| Part | State | Source |
|---|---|---|
| Compose Multiplatform | 1.13.0-rc01 on the branch of step 1, with material3 1.13.0-rc01 and `compose-hot-reload` 1.3.0. `master` has 1.12.2, which does not have the crash fix | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 | `gradle/libs.versions.toml` |
| Android `compileSdk` | 37.1 on the branch of step 1, because the androidx Compose 1.13 libraries need it. `targetSdk` stays 37 | `gradle/libs.versions.toml` |
| Deployment target | iOS 17.0, `TARGETED_DEVICE_FAMILY = "1,2"` | `iosApp/iosApp.xcodeproj/project.pbxproj` |
| Orientations, iPhone | Portrait, landscape left, landscape right | `project.pbxproj` |
| `UIRequiresFullScreen` | Not set | `iosApp/iosApp/Info.plist` |
| Host | A SwiftUI `WindowGroup` holds a `UIViewControllerRepresentable` with `.ignoresSafeArea()`. The Compose content uses `Modifier.safeDrawingPadding()` | `ContentView.swift`, `MainViewController.kt` |
| `UIScreen.main` | Not used in the app code | `grep` of `*.kt` and `*.swift` |
| Two-pane rule | `fitsTwoPanes`: two panes only when the container is wider than it is tall and at least 600 dp wide. A square container shows one column (`squareScreen_showsOneColumn`) | `core/common/.../composable/TwoPaneLayout.kt` |
| Firmware update | The coordinator is not tied to the screen. The upload holds `writeMutex`, and `FirmwareUpdateScreen` uses `Modifier.keepScreenOn()` | `CLAUDE.md`, `FirmwareUpdateScreen.kt` |
| UI tests | `testSimulator_rotationKeepsStateAndEdgeSwipeGoesBack` rotates the simulator with the simulated board and checks that the screen and an unsaved edit stay. No test folds the device | `iosApp/iosAppUITests/SnapshotPBUITests.swift` |
| Xcode on CI | `macos-latest` with the default Xcode of the runner image. No workflow selects an Xcode version | `validation.yml`, `build-release.yml` |

The app thus runs in the compatibility mode on iPhone Duo today. The crash is a large risk.
A fold during a firmware upload can stop the app, and the board then has a partial image in
`slot1`. MCUboot keeps the old image in `slot0`, so the board starts again, but the user
must do the update again.

## 4. Work items in order

### Step 0. Record the baseline

1. Install Xcode 27.1 on the development Mac.
2. Install the iPhone Duo simulator runtime. Record its exact name in this document.
3. Run the current `master` build on the iPhone Duo simulator with `--fake-board`.
4. Fold and unfold the simulator on each screen of the app.
5. Record the result: the compatibility mode on each display, and each crash with its stack.

### Step 1. Move to a Compose Multiplatform version with the fix

1. Make the change on a new branch. Do not merge the Dependabot toolchain branch: it is now
   older than `master`.
2. Set `compose-multiplatform` to 1.13.0-rc01 (decision 1). Change it to 1.13.0 when that
   version is stable.
3. Align `material3` and `compose-hot-reload` with that CMP version.
4. Run `./gradlew -p composite clean test publish` and commit `composite/m2`.
   The Kotlin version is not in the digest of `composite`, so CLAUDE.md requires this
   manual republish.
5. Run the five CI targets.
6. Do step 0 again with the new build. Make sure that no fold stops the app.

Result on 2026-11-21 (branch `feat/iphone-duo-cmp-1.13`):

- Kotlin stays 2.4.21, so `composite` needs no republish (item 4).
- material3 1.13 makes `ExposedDropdownMenu` an extension function of
  `ExposedDropdownMenuBoxScope`. `ConfigureDeviceScreen.kt` and `ProfileScreen.kt` now import it.
- CMP 1.13 deprecates `compose.desktop.currentOs`. `compose.ui` now brings the Skiko runtime of
  each desktop OS (`skiko-awt-runtime-all`), and the packaging removes the binaries of the
  other systems. `app/desktop` thus does not declare `currentOs`.
- The androidx Compose 1.13 libraries need `compileSdk` 37.1. The catalog has
  `android-compileSdkMinor = "1"`, and each Android module sets
  `compileSdk { version = release(37) { minorApiLevel = 1 } }`.
- The Compose plugin warns that Coil 3.6.3 uses Skiko 0.150.1 and CMP 1.13 uses 0.156.1.
  Coil 3.6.3 is the newest Coil. Examine the device photos on desktop and iOS in step 5.5.
- The `android`, `desktop`, `browser` and `ios` targets pass on Linux. The `macos` target
  and item 6 need a Mac.

If the crash stays with 1.13.0, find the call that starts it before step 2. Report it on
CMP-10776.

### Step 2. Build the app with the iOS 27.1 SDK

The SDK of the Xcode that links the app executable sets the mode on iPhone Duo. The Kotlin
framework does not set it.

1. Make sure that the Kotlin version of step 1 supports Xcode 27.1. Kotlin gives a warning
   for an Xcode version that it does not know.
2. In `validation.yml` (target `macos`) and in `build-release.yml` (job `publish-ios`),
   select Xcode 27.1 before the build. Use `sudo xcode-select -s /Applications/Xcode_27.1.app`,
   or a third-party action that is pinned to a full commit SHA.
3. Make sure that the `macos-latest` runner image has Xcode 27.1. If it does not have it, use
   the runner label that has it.
4. Keep `IPHONEOS_DEPLOYMENT_TARGET = 17.0`. The new SDK does not need a new deployment target.
5. Run the app on the iPhone Duo simulator. Make sure that it fills each display to the edge.

### Step 3. Insets near the status bar and the camera

With the iOS 27.1 SDK, the app goes to the edge of the screen. The status bar and the camera
of the inner display are at a new position. The Compose top bar is custom UI for UIKit, so
UIKit does not move it.

1. On each display and in each orientation, make sure that `safeDrawingPadding()` keeps the
   top bar, the back button and the action buttons clear of the status bar and the camera.
2. If CMP reports an inset that is not correct, record it and add the gap to CMP-10776.
   Do not add a fixed inset in `MainViewController.kt`.
3. Do not use `UIViewReservedRegion` in this plan. Kotlin/Native has no binding for it
   (section 2), and `safeDrawingPadding()` gives a correct result with less screen area.

### Step 4. Layouts for each display

The outer display has a phone size, so the current rules apply. The inner display is
approximately 669 pt wide and has the regular size classes. In portrait it is taller than it
is wide, so `fitsTwoPanes` gives one column at the full width of 669 dp. In landscape it
gives two panes.

1. Get the exact size of the inner display in dp, in portrait and in landscape, from the
   simulator. Record it in this document.
2. Examine each screen that calls `fitsTwoPanes` on the inner display, in both orientations:
   `DeviceBrowserScreen`, `ConfigureDeviceScreen`, `ProfilesScreen`, `ProfileScreen`,
   `FirmwareUpdateScreen`. Also examine `ConnectingDeviceScreen`, which has a layout of its own.
3. Change the two-pane rule only as decision 2 tells.
4. Add the sizes of step 4.1 to `TwoPaneLayoutTest`, with the result that decision 2 gives.
5. Make sure that the timing diagram of `ProfileScreen` and the panel drawing of the display
   area are not too wide for a 669 dp column.

The two-pane rule is in `core:common`, so a change also applies to Android, desktop, the web
and iPad. Examine the Android foldables and the iPad in portrait after each change.

### Step 5. Continuity at a fold

A fold changes the size of the window. It does not start the app again.

1. Make sure that each screen keeps its navigation back stack and its unsaved edits at a fold
   and at an unfold.
2. Make sure that the Bluetooth connection to a board stays up at a fold. The simulator has no
   CoreBluetooth, so this check needs a real iPhone Duo and a real board. Decision 3 makes it
   an open item that does not stop the release.
3. Start a firmware update with the simulated board. Fold and unfold during the upload, the
   reset and the swap wait. Make sure that the update continues to its result.
4. Make sure that the screen stays on during an update on the outer display after a fold.
   Record what iOS does to the app at a fold during an update.
5. Make sure that the sheets, the dialogs (for example the stop confirmation of the update),
   the keyboard and the photo picker stay correct at a fold.

### Step 6. Tests

1. Add `testSimulator_foldKeepsStateAndLayout` to `SnapshotPBUITests.swift`. Use the same
   procedure as the rotation test, with a fold in place of a rotation. Find which API folds the
   simulator from a test (`XCUIDevice`, `simctl`, or the Device Hub). If no API can do it, write
   a manual check list in `docs/manual` in its place.
2. Add a fold during a firmware update to the same test, with the simulated board.
3. Make sure that the `macos` CI target still runs only the tests that do not need the
   iPhone Duo simulator, if the runner image does not have that runtime.

### Step 7. Documentation

1. Update the iOS line of the targets in `CLAUDE.md`: iPhone Duo, the minimum CMP version,
   and the Xcode version.
2. Update the "Responsive UI" line in `CLAUDE.md` if decision 2 changes the two-pane rule.
3. Update the CI section of `CLAUDE.md` with the Xcode selection.

## 5. Decisions for the owner

The owner made these decisions on 2026-11-18.

1. **CMP version: the beta on a branch.** Use 1.13.0-beta02 on a branch for steps 0 to 6.
   On 2026-11-21 the owner changed this to 1.13.0-rc01, which came after beta02.
   Release only with the stable 1.13.0 (estimated mid-December). Before the release, change
   the branch to the stable 1.13.0 and do step 1.5 and step 1.6 again.
2. **Two panes on the inner display in portrait: decide after step 4.1.** The rule now gives
   one column for a container that is taller than it is wide. With the real size of step 4.1,
   select one option:
   - Option A: keep the rule. One column at 669 dp is the same as an iPad in portrait.
   - Option B: show two panes when the width is at least 600 dp and the height is less than a
     limit, for example 1.25 times the width. This also changes the iPad mini and Android foldables.
   - Option C: keep one column, but limit its content width so that the fields do not stretch.
3. **Test device: the simulator is sufficient.** The release needs only the simulator tests with
   the simulated board. The simulator cannot test Bluetooth, so step 5.2 is an open item after
   the release. Record it in the report of the work.
4. **Release time: after all steps.** One App Store release comes when steps 0 to 7 are complete
   and the stable CMP 1.13.0 is available.

## 6. Not in this plan

- **Hinge and the fold region** (`UIHingeInteraction`, the "division" region). The app has no
  layout that puts content on each side of the fold, so it does not need the fold position.
  Kotlin/Native also has no binding for these APIs.
- **`UIViewReservedRegion`.** Step 3 tells why.
- **More than one window.** The app has one window. iOS has no public API to put two windows side
  by side on iPhone Duo.
- **The camera on the outer display.** The device photo uses `imagepickerkmp`. Examine the photo
  flow in step 5.5 only.

## 7. Risks

| Risk | Effect | Control |
|---|---|---|
| The crash stays after 1.13.0 | The app stops at a fold, also during a firmware upload | Step 1.6, step 5.3; report on CMP-10776 |
| A fold during a firmware upload | The upload stops, and the user must start it again. The board keeps the old image | Step 5.3; the coordinator already gives a retry |
| The insets of CMP are not correct near the new camera position | A button is below the camera and the user cannot touch it | Step 3 |
| The runner image has no Xcode 27.1 or no iPhone Duo runtime | CI cannot build with the new SDK or run the fold test | Step 2.3, step 6.3 |
| Kotlin does not support Xcode 27.1 | The framework link fails or gives a warning | Step 2.1; a Kotlin patch version |
| The sizes in section 1 are not correct | The two-pane decision uses wrong values | Step 4.1 measures them |
| Apple sets a deadline for the iOS 27 SDK on the App Store | An old build cannot go to the App Store. One third-party source names April 2027, and Apple has not confirmed it | Step 2 |

## 8. Sources

- Apple, "Prepare your app for iPhone Duo", tech talk 111461:
  https://developer.apple.com/videos/play/tech-talks/111461/
- 9to5Mac, "Here's how iPhone Duo treats apps not optimized for the foldable display", 2026-09-09:
  https://9to5mac.com/2026/09/09/heres-how-iphone-duo-treats-apps-not-optimized-for-the-foldable-display/
- Librechat-Mobile issue #602, the CMP crash at a fold:
  https://github.com/garfiec/Librechat-Mobile/issues/602
- Theodo showroom for CMP on foldables:
  https://github.com/theodo-group/compose-multiplatforme-duo-fold-showroom

## 9. Results on the dev Mac (2026-11-21)

These results come from the iPhone Duo simulator on the dev MacBook, with the Debug build of
commit f238df4 (CMP 1.13.0-rc01) and the simulated board (`--fake-board`).

### Tools

| Item | Value |
|---|---|
| Xcode | 27.2 (27B52), iOS 27.2 SDK. The app links with this SDK, so it does not run in the compatibility mode |
| Simulator runtime | `iOS 27.2 (24B91016s)`, `com.apple.CoreSimulator.SimRuntime.iOS-27-2-iPhone19-4`. `xcodebuild -downloadPlatform iOS` installed it together with the normal iOS 27.2 runtime |
| Device type | `com.apple.CoreSimulator.SimDeviceType.iPhone-Duo` |
| Fold control | Only the Closed, Partially Open and Open buttons of the Xcode Device Hub. XCTest, XCUIAutomation and `simctl` have no fold API. `simctl io screenConfig power off` turns a screen off but does not fold the device, and it made SpringBoard crash |

### Window sizes (step 4.1)

| State | Window, points | Status bar | `fitsTwoPanes` |
|---|---|---|---|
| Folded, outer display | 466 x 678 | A column at the right edge, about 84 pt wide | One column |
| Open, inner display | 951 x 669 | A column at the right edge | Two panes |
| Open, inner display, turned with the Device Hub | 669 x 951 | A strip at the top | One column |

The device opens in the 951 x 669 state. `XCUIDevice.orientation` does not turn the inner
display. Thus decision 2 is about the 669 x 951 state only, which the user gets when they turn
the open device.

### Fold and unfold (steps 1.6, 5 and 6)

`testSimulator_foldKeepsStateAndLayout` (`SnapshotPBUITests.swift`) passed with 14 changes on 7
screens: the device list, Configure Device, the update confirm page, the update while it runs,
the Bluetooth log, the Profiles screen and one profile. A host script clicked the Device Hub
buttons when the test printed `===FOLD_NOW===`.

- The crash of section 2 did not occur. The app process stayed the same, and no crash report
  came, also 5 s after each change.
- The screen and its back stack stayed. The unsaved boot text and the unsaved profile name stayed.
- A firmware update in the download step continued through a fold and an unfold, and ended with
  `done`.
- The test skips when `RUN_FOLD_TESTS` is not 1. To run it by hand: start the test with
  `TEST_RUNNER_RUN_FOLD_TESTS=1` on a folded iPhone Duo simulator, and click Open or Closed in
  the Device Hub each time that the log shows `===FOLD_NOW===`. CI does not run the XCUITests, so
  the `macos` target needs no iPhone Duo runtime (step 6.3).

### Insets (step 3)

`safeDrawingPadding()` keeps the content clear of the status bar column on each display. No
button is under the status bar or the camera. The inset areas show the window background, not
the app background: white in light mode and black in dark mode. On iPhone Duo these areas are
larger than on other iPhone models, so the white column at the right is easy to see in light
mode. This is an open item for the owner.

### Not done yet

- Step 0 with the `master` build (CMP 1.12.2), to see if the crash occurs on this app.
- Step 5.2 (Bluetooth at a fold) needs a real iPhone Duo (decision 3).
- Step 5.4 (the screen stays on during an update after a fold) and step 5.5 (sheets, dialogs, the
  keyboard and the photo picker at a fold).
