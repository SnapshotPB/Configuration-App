import XCTest

/// End-to-end UI tests for the SnapshotPB iOS app, driving the real Compose
/// Multiplatform UI through the iOS accessibility bridge.
///
/// Compose `Modifier.testTag("x")` is exposed to XCUITest as an element's
/// `accessibilityIdentifier`, so the tags added in `core:common` are how we
/// locate UI here: `btAcceptPermission`, `btStartScanning`, `btStopScanning`,
/// `discoveredDevice:<name>`, `btFinishConfiguration`, `connectionFailed`.
///
/// - ``testSmoke_appLaunchesAndStartsScanning`` always runs: it verifies the BLE
///   stack initialises, permission is granted, and scanning begins. No board needed —
///   and on a simulator, which has no CoreBluetooth, no radio either (see the test).
/// - ``testHardware_connectToBoard`` is gated behind `RUN_HARDWARE_TESTS=1` and needs
///   a powered Snapshot PB board in range. Optionally target a specific board with
///   `BOARD_NAME=<name>`. Pass these to `xcodebuild` as `TEST_RUNNER_`-prefixed vars,
///   e.g. `TEST_RUNNER_RUN_HARDWARE_TESTS=1`.
final class SnapshotPBUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
        // The Compose screens also lay out in landscape, and XCUITest then reports buttons on the right
        // side as not hittable. Portrait keeps every run the same.
        XCUIDevice.shared.orientation = .portrait
    }

    // MARK: - Tests

    /// Smoke test: launch → (grant BLE permission) → reach the Scanning state.
    ///
    /// On a physical device this walks the real CoreBluetooth path. The iOS Simulator has no
    /// CoreBluetooth at all — `CBCentralManager` comes up `.unsupported` and stays there, so the app
    /// never reaches Ready and there is nothing to assert. There the test launches with
    /// `--fake-board` instead, which binds the simulated board in place of the radio and lets the
    /// same launch → permission → scan flow run. What it covers differs accordingly: the real BLE
    /// stack on a device, and everything above the radio on a simulator.
    func testSmoke_appLaunchesAndStartsScanning() {
        #if targetEnvironment(simulator)
        let app = launchApp(fakeBoard: true)
        #else
        let app = launchApp()
        #endif
        startScanning(app)
        XCTAssertTrue(
            element(app, "btStopScanning").waitForExistence(timeout: 20),
            "App did not reach the Scanning state (BLE init / permission / scan start failed)."
        )
    }

    /// Insets: the content stays clear of the status bar, and the keyboard does not cover the field
    /// that has the focus. The test uses the simulated board, so it runs on a simulator with no
    /// radio. Run it on a simulator with a Dynamic Island (for example iPhone 17) and on an iPad.
    func testSimulator_insetsKeepContentVisible() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Simulator test. It uses the simulated board.")
        #else
        let app = launchApp(fakeBoard: true)
        let title = app.staticTexts["Devices"]
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Devices not shown. \(dumpTree(app))")
        let statusBar = XCUIApplication(bundleIdentifier: "com.apple.springboard").statusBars.firstMatch
        let topLimit = statusBar.exists ? statusBar.frame.maxY : 20
        print("===INSETS=== title=\(title.frame) statusBarMaxY=\(topLimit)")
        XCTAssertGreaterThanOrEqual(title.frame.minY, topLimit, "The title is under the status bar.")

        startScanning(app)
        discoverAndTapBoard(app, boardName: nil)
        XCTAssertTrue(awaitConnectingScreenSucceeds(app, timeoutSec: 30),
                      "The simulated board did not connect. \(connectingProgressDump(app))")
        element(app, "btFinishConfiguration").tap()
        XCTAssertTrue(app.staticTexts["Configure Device"].waitForExistence(timeout: 15),
                      "Configure Device not shown. \(dumpTree(app))")

        // In landscape the keyboard covers most of the screen, so it opens over the boot text field.
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        sleep(1)
        let field = textInput(app, index: 1)
        print("===INSETS=== field before the tap=\(field.frame)")
        field.tap()
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 10), "The keyboard did not open.")
        sleep(1) // Let the keyboard animation and the inset change finish.
        // An index query can point to a different field after the layout changes, so find the
        // field by its focus.
        let focused = app.textViews.matching(NSPredicate(format: "hasKeyboardFocus == true")).firstMatch
        XCTAssertTrue(focused.exists, "No text field has the keyboard focus. \(dumpTree(app))")
        print("===INSETS=== focused=\(focused.frame) keyboard=\(keyboard.frame)")
        XCTAssertLessThanOrEqual(focused.frame.maxY, keyboard.frame.minY, "The keyboard covers the field.")
        #endif
    }

    /// Full hardware E2E: scan → tap a discovered board → walk the 4-step connect
    /// (connect → discover services → read device info → sync profiles) → confirm
    /// completion. Requires a real board in range; otherwise skipped.
    func testHardware_connectToBoard() throws {
        let env = ProcessInfo.processInfo.environment
        guard envFlag(env, "RUN_HARDWARE_TESTS") == "1" else {
            throw XCTSkip("Hardware test skipped. Run with TEST_RUNNER_RUN_HARDWARE_TESTS=1 and a board in range.")
        }
        let boardName = envFlag(env, "BOARD_NAME")

        let app = launchApp()
        startScanning(app)

        // Wait for at least one discovered board to appear in the scan list.
        let anyBoard = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "discoveredDevice:"))
            .firstMatch
        XCTAssertTrue(anyBoard.waitForExistence(timeout: 30),
                      "No Snapshot PB board was discovered within 30s.")

        let target: XCUIElement
        if let boardName, !boardName.isEmpty {
            target = discoveredBoard(app, named: boardName)
            XCTAssertTrue(target.waitForExistence(timeout: 30),
                          "Board '\(boardName)' was not discovered.")
        } else {
            target = anyBoard
        }
        target.tap()

        // Walk the connect flow: success surfaces the Finish button, failure the retry button.
        let finish = element(app, "btFinishConfiguration")
        let failed = element(app, "connectionFailed")
        let deadline = Date().addingTimeInterval(90)
        var connected = false
        while Date() < deadline {
            if finish.exists { connected = true; break }
            if failed.exists { break }
            usleep(500_000)
        }
        // Read the connecting screen's 4-step progress (encoded into an accessibility id) so the
        // test log names exactly which step failed: connProgress:<step1>-<step2>-<step3>-<step4>.
        let progressEl = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "connProgress:")).firstMatch
        let progress = progressEl.exists ? progressEl.identifier : "(no progress element)"
        print("===CONN PROGRESS=== \(progress)")

        XCTAssertFalse(failed.exists, "Connection failed during the 4-step sync. Progress: \(progress)")
        XCTAssertTrue(connected,
                      "Connection did not complete within 90s. Progress: \(progress)")

        // Save the board, so that it is a known device for the reconnect, write and photo tests.
        finish.tap()
        XCTAssertTrue(app.staticTexts["Configure Device"].waitForExistence(timeout: 15),
                      "Configure Device screen not shown after Finish Configuration. \(dumpTree(app))")
    }

    /// Hardware reconnect E2E — the regression test for the iOS stale-services-on-reconnect bug.
    ///
    /// Opens the known board's Profiles screen and uses its connection card to Connect, Disconnect, then
    /// Connect again — all within the same app session. Because the CBCentralManager lives for the whole
    /// process, CoreBluetooth still holds the previous connection's cached CBService/CBCharacteristic
    /// objects on the reconnect.
    /// With the unfixed BlueFalcon Apple engine those stale (non-empty) services satisfy the app's
    /// `while (services.isEmpty())` discovery gate instantly, so service discovery is "done" on dead
    /// handles and the device-info/profile steps then fail ("is not a valid characteristic"). With the
    /// fix, `services` reports empty until THIS connection's discovery completes, so the gate waits and
    /// the reconnect completes all four steps. Gated behind RUN_HARDWARE_TESTS=1; needs a board in range.
    func testHardware_reconnectToBoard() throws {
        let env = ProcessInfo.processInfo.environment
        guard envFlag(env, "RUN_HARDWARE_TESTS") == "1" else {
            throw XCTSkip("Hardware test skipped. Run with TEST_RUNNER_RUN_HARDWARE_TESTS=1 and a board in range.")
        }
        let app = launchApp()
        grantBluetoothPermission(app)

        // The board was configured once already, so it is a KNOWN device. Tapping a known device opens
        // the Profiles screen, whose connection card drives explicit (re)connections — Connect, then
        // Disconnect, then Connect again, all within one app session (one CBCentralManager). The second
        // Connect is the reconnect where CoreBluetooth still holds the first connection's cached services
        // (the stale-handles bug the fix addresses).
        let board = boardCell(app)
        XCTAssertTrue(board.waitForExistence(timeout: 30),
                      "Known board not listed on the device browser. \(dumpTree(app))")
        board.tap()

        let connect = element(app, "btConnectionConnect")
        let disconnect = element(app, "btConnectionDisconnect")
        let retry = element(app, "btConnectionRetry")

        // Profiles connection card should appear (Disconnected => Connect button).
        let settle = Date().addingTimeInterval(15)
        while Date() < settle { if connect.exists || disconnect.exists || retry.exists { break }; usleep(500_000) }
        XCTAssertTrue(connect.exists || disconnect.exists || retry.exists,
                      "Profiles connection card not shown after tapping the board. \(dumpTree(app))")

        // ---- Connection #1 via the card: establishes the link and discovers services, which
        //      CoreBluetooth then caches across the upcoming disconnect.
        if !disconnect.exists {
            (connect.exists ? connect : retry).tap()
            let r1 = Date().addingTimeInterval(120)
            var first = false
            while Date() < r1 { if disconnect.exists { first = true; break }; if retry.exists { break }; usleep(500_000) }
            XCTAssertTrue(first,
                          "Initial connect via the card did not reach Connected. \(cardProgressDump(app)) \(dumpTree(app))")
        }
        print("===RECONNECT: first connection established===")

        // ---- Disconnect (or accept an auto-drop), then RECONNECT (#2): the stale-services repro.
        if disconnect.exists { disconnect.tap() }
        XCTAssertTrue(connect.waitForExistence(timeout: 25),
                      "Card did not return to Disconnected before reconnect. \(dumpTree(app))")

        // Optional coordinated pause: an external agent restarts the board during this window so its
        // GATT is rebuilt and CoreBluetooth's cached service/characteristic handles become genuinely
        // stale — the precondition that exposes the unfixed engine (the fixed engine re-discovers).
        if let delayStr = envFlag(ProcessInfo.processInfo.environment, "RECONNECT_DELAY_SEC"),
           let delaySec = Double(delayStr), delaySec > 0 {
            print("===RESET_BOARD_NOW===")
            Thread.sleep(forTimeInterval: delaySec)
            print("===RESET_WINDOW_DONE===")
        }

        print("===RECONNECT: issuing second connect===")
        connect.tap()

        // Success => card returns to Connected (Disconnect button reappears).
        // Bug     => card goes to Failed (Retry button), or device-info/profile reads hang on a dead
        //            handle so nothing resolves before the deadline. Both are failures here.
        let deadline = Date().addingTimeInterval(150)
        var reconnected = false
        var failed = false
        while Date() < deadline {
            if disconnect.exists { reconnected = true; break }
            if retry.exists { failed = true; break }
            usleep(500_000)
        }
        let progress = cardProgressDump(app)
        print("===RECONNECT PROGRESS=== \(progress)")
        XCTAssertFalse(failed,
                       "Reconnect FAILED — card went to Failed/Retry. Card progress: \(progress). \(dumpTree(app))")
        XCTAssertTrue(reconnected,
                      "Reconnect did not complete within 150s (likely hung on a stale handle). Card progress: \(progress). \(dumpTree(app))")
        print("===RECONNECT: SUCCESS — card returned to Connected===")
    }

    /// Diagnostic: dumps the accessibility tree so we can confirm Compose testTags
    /// surface as identifiers on iOS. Gated so it never runs by default.
    func testDebug_dumpAccessibilityTree() throws {
        guard envFlag(ProcessInfo.processInfo.environment, "DUMP_TREE") == "1" else {
            throw XCTSkip("Set TEST_RUNNER_DUMP_TREE=1 to dump the accessibility tree.")
        }
        let app = launchApp()
        _ = element(app, "btStartScanning").waitForExistence(timeout: 10)
        print("===ACCESSIBILITY TREE START===")
        print(app.debugDescription)
        print("===ACCESSIBILITY TREE END===")
    }

    // MARK: - Helpers


    // MARK: - Write and link-loss hardware tests
    //
    // These tests use the saved board (run testHardware_connectToBoard first; it taps Finish
    // Configuration). Each test prints ===RESET_BOARD_NOW===, and the host resets the board while the
    // test waits: the link drops, and the board restarts from what it stored in flash. The host also
    // reads the board boot log, which prints the stored boot text and profile names.

    /// Config write: change the boot text, save it to the board, restart the board, reconnect, and
    /// make sure that the board kept the value. Writes the original value back at the end.
    func testHardware_writeBootText() throws {
        let env = ProcessInfo.processInfo.environment
        guard envFlag(env, "RUN_HARDWARE_TESTS") == "1" else {
            throw XCTSkip("Hardware test skipped. Run with TEST_RUNNER_RUN_HARDWARE_TESTS=1 and a board in range.")
        }
        let app = launchApp()
        openConnectedBoard(app)

        let value = "iOS\(Int(Date().timeIntervalSince1970) % 100_000)"
        openConfigureDevice(app)
        let field = textInput(app, index: 1)
        let original = (field.value as? String) ?? ""
        replaceText(field, with: value)
        tapScrolling(app, app.buttons["Save"])
        saveToBoard(app)
        print("===WRITE VALUE=== boot_text=\(value)")

        restartBoardAndReconnect(app)
        assertBoardAgrees(app)

        // Put the original boot text back, so the test leaves the board as it found it.
        if !original.isEmpty && original != value {
            openConfigureDevice(app)
            replaceText(textInput(app, index: 1), with: original)
            tapScrolling(app, app.buttons["Save"])
            saveToBoard(app)
        }
        print("===WRITE BOOT TEXT OK===")
    }

    /// Profile write: rename the profile, save it to the board, restart the board, reconnect, and
    /// make sure that the board kept the name. Writes the original name back at the end.
    /// TEST_RUNNER_PROFILE_NAME names the profile to edit (default "Default").
    func testHardware_writeProfileName() throws {
        let env = ProcessInfo.processInfo.environment
        guard envFlag(env, "RUN_HARDWARE_TESTS") == "1" else {
            throw XCTSkip("Hardware test skipped. Run with TEST_RUNNER_RUN_HARDWARE_TESTS=1 and a board in range.")
        }
        let original = envFlag(env, "PROFILE_NAME") ?? "Default"
        let app = launchApp()
        openConnectedBoard(app)

        let value = "iOS\(Int(Date().timeIntervalSince1970) % 100_000)"
        renameProfile(app, from: original, to: value)
        saveToBoard(app)
        print("===WRITE VALUE=== profile_name=\(value)")

        restartBoardAndReconnect(app)
        assertBoardAgrees(app)
        XCTAssertTrue(app.staticTexts[value].waitForExistence(timeout: 5),
                      "The renamed profile is not listed after the reconnect. \(dumpTree(app))")

        renameProfile(app, from: value, to: original)
        saveToBoard(app)
        print("===WRITE PROFILE NAME OK===")
    }

    /// Link loss: with the link up, the host resets the board. The connection card must go back to
    /// Disconnected, so that the user can not save to a board that is gone.
    func testHardware_linkLossShowsDisconnected() throws {
        let env = ProcessInfo.processInfo.environment
        guard envFlag(env, "RUN_HARDWARE_TESTS") == "1" else {
            throw XCTSkip("Hardware test skipped. Run with TEST_RUNNER_RUN_HARDWARE_TESTS=1 and a board in range.")
        }
        let app = launchApp()
        openConnectedBoard(app)

        let started = Date()
        print("===RESET_BOARD_NOW===")
        XCTAssertTrue(element(app, "btConnectionConnect").waitForExistence(timeout: 30),
                      "The card did not show Disconnected within 30s of the board reset. \(cardProgressDump(app))")
        print("===LINK LOSS DETECTED=== after \(Int(Date().timeIntervalSince(started)))s")
    }

    /// Rotation and the back gesture: a rotation keeps the screen and the text that the user typed,
    /// and a swipe from the left edge goes back one screen. The test uses the simulated board, so it
    /// runs on a simulator (an iPad for the two-pane layouts). XCUITest cannot open Split View.
    func testSimulator_rotationKeepsStateAndEdgeSwipeGoesBack() throws {
        #if !targetEnvironment(simulator)
        throw XCTSkip("Simulator test. It uses the simulated board.")
        #else
        XCUIDevice.shared.orientation = .portrait
        let app = launchApp(fakeBoard: true)
        startScanning(app)
        discoverAndTapBoard(app, boardName: nil)
        XCTAssertTrue(awaitConnectingScreenSucceeds(app, timeoutSec: 30),
                      "The simulated board did not connect. \(connectingProgressDump(app))")
        element(app, "btFinishConfiguration").tap()
        let title = app.staticTexts["Configure Device"]
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Configure Device not shown. \(dumpTree(app))")

        // Change the boot text, then rotate both ways. The screen and the unsaved text must stay.
        let field = textInput(app, index: 1)
        field.tap()
        field.typeText("R")
        let typed = field.value as? String
        dismissKeyboard(app)
        for orientation in [UIDeviceOrientation.landscapeLeft, .portrait, .landscapeRight, .portrait] {
            XCUIDevice.shared.orientation = orientation
            sleep(1)
            XCTAssertTrue(title.exists, "Configure Device went away after the rotation to \(orientation.rawValue).")
            let value = textInput(app, index: 1).value as? String
            XCTAssertEqual(value, typed, "The typed boot text changed after the rotation to \(orientation.rawValue).")
        }

        // A swipe from the left edge goes back one screen, on each screen of the path.
        edgeSwipeBack(app)
        assertGone(title, "Configure Device")
        let devices = app.staticTexts["Devices"]
        // The Profiles screen shows the device name as its title, so find it by its connection card.
        let profiles = app.staticTexts["Board Connection"]
        if !devices.waitForExistence(timeout: 5) {
            // Finish Configuration can leave the Profiles screen under Configure Device.
            XCTAssertTrue(profiles.exists, "Neither Devices nor the Profiles screen after the back. \(dumpTree(app))")
            edgeSwipeBack(app)
            XCTAssertTrue(devices.waitForExistence(timeout: 10), "Devices not shown after the back. \(dumpTree(app))")
        }

        // Devices, then the Profiles screen, then one profile, and back two times.
        boardCell(app).tap()
        XCTAssertTrue(profiles.waitForExistence(timeout: 15), "The Profiles screen is not shown. \(dumpTree(app))")
        let profileRow = app.staticTexts.matching(NSPredicate(format: "label == %@", "Semi")).firstMatch
        XCTAssertTrue(profileRow.waitForExistence(timeout: 10), "No profile row. \(dumpTree(app))")
        profileRow.tap()
        XCTAssertTrue(profiles.waitForNonExistence(timeout: 10), "The profile did not open. \(dumpTree(app))")
        edgeSwipeBack(app)
        XCTAssertTrue(profiles.waitForExistence(timeout: 10), "The back from the profile failed. \(dumpTree(app))")
        edgeSwipeBack(app)
        XCTAssertTrue(devices.waitForExistence(timeout: 10), "The back from the Profiles screen failed. \(dumpTree(app))")
        print("===BACK GESTURE=== ok on Configure Device, Profile and Profiles")
        #endif
    }

    /// Swipes from the left edge of the window to the right, as a user does to go back.
    private func edgeSwipeBack(_ app: XCUIApplication) {
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.005, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
    }

    private func assertGone(_ element: XCUIElement, _ name: String) {
        XCTAssertTrue(element.waitForNonExistence(timeout: 10), "\(name) is still shown after the back gesture.")
    }

    /// Opens the saved board and makes sure that the link is up.
    private func openConnectedBoard(_ app: XCUIApplication) {
        grantBluetoothPermission(app)
        let board = boardCell(app)
        XCTAssertTrue(board.waitForExistence(timeout: 30), "Known board not listed. \(dumpTree(app))")
        board.tap()
        connectViaCard(app)
    }

    /// Taps Connect on the Profiles connection card (unless the link is up already) and waits for it.
    private func connectViaCard(_ app: XCUIApplication, timeout: TimeInterval = 60) {
        let connect = element(app, "btConnectionConnect")
        let disconnect = element(app, "btConnectionDisconnect")
        let retry = element(app, "btConnectionRetry")
        let settle = Date().addingTimeInterval(15)
        while Date() < settle { if connect.exists || disconnect.exists || retry.exists { break }; usleep(500_000) }
        if !disconnect.exists {
            (connect.exists ? connect : retry).tap()
        }
        XCTAssertTrue(disconnect.waitForExistence(timeout: timeout),
                      "The card did not reach Connected. \(cardProgressDump(app))")
        dismissConflictIfShown(app)
    }

    /// Asks the host to reset the board, waits until the app sees the link drop, and connects again.
    private func restartBoardAndReconnect(_ app: XCUIApplication) {
        print("===RESET_BOARD_NOW===")
        XCTAssertTrue(element(app, "btConnectionConnect").waitForExistence(timeout: 30),
                      "The app did not see the link drop after the board reset. \(cardProgressDump(app))")
        connectViaCard(app)
    }

    /// After a reconnect the app compares the board with its own copy. A difference shows the
    /// "Device settings differ" dialog or the "Unsaved Changes" banner.
    private func assertBoardAgrees(_ app: XCUIApplication) {
        XCTAssertFalse(app.staticTexts["Device settings differ"].waitForExistence(timeout: 5),
                       "The board did not keep the written value: the app shows a conflict. \(dumpTree(app))")
        XCTAssertFalse(app.staticTexts["Unsaved Changes"].exists,
                       "The board did not keep the written value: the app shows unsaved changes. \(dumpTree(app))")
    }

    /// A conflict before the write means the board changed since the last read (for example after an
    /// earlier failed run). Take the board values, so the write starts from the board state.
    private func dismissConflictIfShown(_ app: XCUIApplication) {
        let useDevice = app.buttons["Use device values"]
        if useDevice.waitForExistence(timeout: 3) {
            print("===CONFLICT BEFORE WRITE: taking the device values===")
            useDevice.tap()
        }
    }

    private func openConfigureDevice(_ app: XCUIApplication) {
        tapScrolling(app, app.buttons["Configure"])
        XCTAssertTrue(app.staticTexts["Configure Device"].waitForExistence(timeout: 10),
                      "Configure Device screen did not open. \(dumpTree(app))")
    }

    private func renameProfile(_ app: XCUIApplication, from: String, to: String) {
        tapScrolling(app, app.staticTexts[from])
        let field = textInput(app, index: 0)
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Profile editor did not open. \(dumpTree(app))")
        replaceText(field, with: to)
        tapScrolling(app, app.buttons["Save"])
        XCTAssertTrue(app.staticTexts[to].waitForExistence(timeout: 10),
                      "Profiles screen did not list the new name '\(to)'. \(dumpTree(app))")
    }

    /// Sends the pending changes and waits until the banner goes away, which means the write passed.
    private func saveToBoard(_ app: XCUIApplication) {
        let banner = app.staticTexts["Unsaved Changes"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "Unsaved Changes banner did not appear. \(dumpTree(app))")
        tapScrolling(app, app.buttons["Save to Board"])
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline && banner.exists { usleep(500_000) }
        XCTAssertFalse(banner.exists, "The write to the board did not complete within 30s. \(dumpTree(app))")
    }

    /// A Compose text field. Compose Multiplatform shows it to XCUITest as a TextView, not a TextField.
    private func textInput(_ app: XCUIApplication, index: Int) -> XCUIElement {
        let field = app.textViews.element(boundBy: index)
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Text field \(index) not found. \(dumpTree(app))")
        return field
    }

    private func replaceText(_ field: XCUIElement, with text: String) {
        field.tap()
        let current = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        field.typeText(text)
        XCTAssertEqual(field.value as? String, text, "The field did not keep the typed text.")
    }

    /// Closes the keyboard, then scrolls until [element] exists and is hittable, then taps it. The
    /// Compose screens are lazy lists taller than the phone, so an element such as Save is not in the
    /// tree until it scrolls into view.
    private func tapScrolling(_ app: XCUIApplication, _ element: XCUIElement) {
        dismissKeyboard(app)
        var swipes = 0
        while !(element.exists && element.isHittable && !isUnderAddProfileButton(app, element)) && swipes < 12 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(element.exists, "Element not found after \(swipes) swipes: \(element). \(dumpTree(app))")
        element.tap()
        // While a text field has focus, Compose uses the first tap only to end the focus. Tap again
        // when the element is still there. A Save to Board button is disabled during the write.
        usleep(1_500_000)
        if element.exists && element.isHittable && element.isEnabled {
            element.tap()
        }
    }

    /// Compose does not tell XCUITest that the Add Profile button floats above the list, so
    /// `isHittable` stays true for an element under it. On a small iPhone (SE, 375 x 667 points) that
    /// button covers the center of Configure, and a tap there opens Create Profile.
    private func isUnderAddProfileButton(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        let addProfile = app.buttons["Add Profile"]
        guard addProfile.exists, element.label != "Add Profile" else { return false }
        return addProfile.frame.contains(CGPoint(x: element.frame.midX, y: element.frame.midY))
    }

    private func dismissKeyboard(_ app: XCUIApplication) {
        guard app.keyboards.count > 0 else { return }
        for key in ["Done", "done", "Return", "return"] {
            let button = app.keyboards.buttons[key]
            if button.exists { button.tap(); break }
        }
        _ = app.keyboards.element.waitForNonExistence(timeout: 3)
    }



    // MARK: - Photo hardware tests
    //
    // The host lists the app data container before and after each test and looks for a new
    // device_photo_* file, which proves that the app stored the photo.

    func testHardware_photoFromCamera() throws {
        let app = openDevicePhotoScreen()
        app.buttons["Take Photo"].tap()
        allowSystemAlert(["Allow", "OK"])
        let shutter = app.buttons["PhotoCapture"]
        XCTAssertTrue(shutter.waitForExistence(timeout: 15), "Camera shutter not found. \(dumpTree(app))")
        shutter.tap()
        let usePhoto = app.buttons["Use Photo"]
        XCTAssertTrue(usePhoto.waitForExistence(timeout: 15), "Use Photo button not found. \(dumpTree(app))")
        usePhoto.tap()
        saveDevicePhoto(app)
        print("===PHOTO FROM CAMERA SAVED===")
    }

    func testHardware_photoFromGallery() throws {
        let app = openDevicePhotoScreen()
        app.buttons["Select from Gallery"].tap()
        allowSystemAlert(["Allow Full Access", "Allow Access to All Photos", "Allow", "OK"])
        let photos = app.navigationBars["Photos"]
        XCTAssertTrue(photos.waitForExistence(timeout: 15), "Photo picker did not open. \(dumpTree(app))")
        // The picker sits over the app, so select a library item by the picker grid identifier. Its
        // label starts with "Photo," or "Live Photo,".
        let firstImage = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        XCTAssertTrue(firstImage.waitForExistence(timeout: 10),
                      "The photo library has no image. Take one photo with the Camera app first. \(dumpTree(app))")
        // The picker runs out of process, so XCUITest reports its cells as not hittable. Tap the
        // centre of the cell by coordinate.
        firstImage.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        for label in ["Add", "Done"] {
            let button = app.buttons[label]
            if button.waitForExistence(timeout: 2) { button.tap(); break }
        }
        saveDevicePhoto(app)
        print("===PHOTO FROM GALLERY SAVED===")
    }

    /// Opens the saved board from the device list. That screen shows the photo buttons and Save Device.
    private func openDevicePhotoScreen() -> XCUIApplication {
        let app = launchApp()
        grantBluetoothPermission(app)
        let board = boardCell(app)
        XCTAssertTrue(board.waitForExistence(timeout: 30), "Known board not listed. \(dumpTree(app))")
        board.tap()
        XCTAssertTrue(app.buttons["Take Photo"].waitForExistence(timeout: 15),
                      "The photo buttons are not shown. \(dumpTree(app))")
        return app
    }

    private func allowSystemAlert(_ labels: [String]) {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            for label in labels where springboard.buttons[label].exists {
                springboard.buttons[label].tap()
                return
            }
            usleep(300_000)
        }
    }

    private func saveDevicePhoto(_ app: XCUIApplication) {
        // The app stores the photo when the picker returns. A Save Device button can follow; tap it
        // when it is there. The host checks the stored file.
        let save = app.buttons["Save Device"]
        if save.waitForExistence(timeout: 5) { tapScrolling(app, save) }
        sleep(2)
    }

    private func launchApp(fakeBoard: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        if fakeBoard {
            app.launchArguments.append(fakeBoardArgument)
        }
        app.launch()
        return app
    }

    /// Launch argument that swaps the radio for the simulated board of `core:fake-board`. It is
    /// read in `MainViewController.startOnce()`, and a release framework does not carry the board
    /// at all, so the argument does nothing there.
    private var fakeBoardArgument: String { "--fake-board" }

    /// Locates a Compose element by its testTag (accessibilityIdentifier), any type.
    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        return app.descendants(matching: .any)[id].firstMatch
    }

    /// A discovered board by name. An unsaved board has an empty name, so its tag holds the device id
    /// and only its label shows the default name. Match the tag or the label.
    private func discoveredBoard(_ app: XCUIApplication, named boardName: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier == %@ OR (identifier BEGINSWITH %@ AND label CONTAINS %@)",
            "discoveredDevice:\(boardName)", "discoveredDevice:", boardName
        )).firstMatch
    }

    /// Waits for a discovered board to appear, optionally one named `boardName`, then taps it.
    private func discoverAndTapBoard(_ app: XCUIApplication, boardName: String?) {
        let anyBoard = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "discoveredDevice:"))
            .firstMatch
        XCTAssertTrue(anyBoard.waitForExistence(timeout: 30),
                      "No Snapshot PB board was discovered within 30s.")
        let target: XCUIElement
        if let boardName, !boardName.isEmpty {
            target = discoveredBoard(app, named: boardName)
            XCTAssertTrue(target.waitForExistence(timeout: 30), "Board '\(boardName)' was not discovered.")
        } else {
            target = anyBoard
        }
        target.tap()
    }

    /// On the connecting screen, polls until the 4-step flow surfaces Finish (true) or fails (false).
    private func awaitConnectingScreenSucceeds(_ app: XCUIApplication, timeoutSec: TimeInterval) -> Bool {
        let finish = element(app, "btFinishConfiguration")
        let failed = element(app, "connectionFailed")
        let deadline = Date().addingTimeInterval(timeoutSec)
        while Date() < deadline {
            if finish.exists { return true }
            if failed.exists { return false }
            usleep(500_000)
        }
        return false
    }

    /// Reads the connecting screen's encoded 4-step progress id (connProgress:<s1>-<s2>-<s3>-<s4>).
    private func connectingProgressDump(_ app: XCUIApplication) -> String {
        let el = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "connProgress:")).firstMatch
        return el.exists ? el.identifier : "(no connProgress element)"
    }

    /// Reads the connection card's encoded 4-step progress id (cardConnProgress:<s1>-<s2>-<s3>-<s4>).
    private func cardProgressDump(_ app: XCUIApplication) -> String {
        let el = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "cardConnProgress:")).firstMatch
        return el.exists ? el.identifier : "(no cardConnProgress element)"
    }

    /// Dumps the current accessibility tree into a failure message for diagnosis.
    private func dumpTree(_ app: XCUIApplication) -> String {
        return "\n===ACCESSIBILITY TREE===\n\(app.debugDescription)\n===END TREE==="
    }

    /// Grants the Bluetooth permission if prompted and waits until the browser reaches the Ready state
    /// (the "Start Scanning" affordance appears), WITHOUT starting a scan — so the known-devices list,
    /// which routes a tap to the Profiles screen, is what's shown.
    private func grantBluetoothPermission(_ app: XCUIApplication) {
        let accept = element(app, "btAcceptPermission")
        if accept.waitForExistence(timeout: 5) {
            accept.tap()
            allowBluetoothIfPrompted()
        }
        _ = element(app, "btStartScanning").waitForExistence(timeout: 20)
    }

    /// Locates the (known) Snapshot board's list row. Known rows tag the name Text with the device
    /// name (`testTag(device.name)`); fall back to any static text containing "snapshot".
    private func boardCell(_ app: XCUIApplication) -> XCUIElement {
        // The app gives a new device the default name "SnapshotPB" (`device_default_name`).
        let exact = element(app, "SnapshotPB")
        if exact.waitForExistence(timeout: 8) { return exact }
        // An older build saved the board as "Snapshot P" or "Snapshot PB". Find those names too.
        let saved = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "Snapshot")).firstMatch
        if saved.exists { return saved }
        return app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "snapshot")).firstMatch
    }

    /// Reads a flag from either the plain name or the `TEST_RUNNER_`-prefixed name
    /// (xcodebuild forwards command-line env vars to the runner with that prefix).
    private func envFlag(_ env: [String: String], _ name: String) -> String? {
        return env[name] ?? env["TEST_RUNNER_\(name)"]
    }

    /// Grants Bluetooth permission if prompted, then taps "Start Scanning".
    /// No-op if the app is already scanning (e.g. on relaunch).
    private func startScanning(_ app: XCUIApplication) {
        let stop = element(app, "btStopScanning")
        if stop.waitForExistence(timeout: 2) { return }

        let accept = element(app, "btAcceptPermission")
        if accept.waitForExistence(timeout: 3) {
            accept.tap()
            allowBluetoothIfPrompted()
        }

        let start = element(app, "btStartScanning")
        XCTAssertTrue(start.waitForExistence(timeout: 20),
                      "Bluetooth never reached the Ready state — is Bluetooth enabled?")
        start.tap()
    }

    /// Dismisses the system Bluetooth permission alert (presented by SpringBoard).
    @discardableResult
    private func allowBluetoothIfPrompted(timeout: TimeInterval = 10) -> Bool {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let labels = ["Allow", "OK", "Allow While Using App"]
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for label in labels {
                let button = springboard.buttons[label]
                if button.exists && button.isHittable {
                    button.tap()
                    return true
                }
            }
            usleep(300_000)
        }
        return false
    }
}
