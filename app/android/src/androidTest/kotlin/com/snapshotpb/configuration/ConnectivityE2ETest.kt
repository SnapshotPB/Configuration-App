package com.snapshotpb.configuration

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.snapshotpb.configuration.activity.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class ConnectivityE2ETest {

    private lateinit var device: UiDevice
    private val packageName = "com.snapshotpb.configuration"

    @Before
    fun setUp() {
        // Initialize UiDevice
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Grant permissions programmatically
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        val context = instrumentation.targetContext

        // Grant every permission the app might need across the supported API range (minSdk 24).
        // BLUETOOTH_SCAN/CONNECT are runtime permissions only on API 31+; on older devices BLE
        // scanning instead requires ACCESS_FINE/COARSE_LOCATION. Granting an unknown permission
        // throws on the running OS, so attempt each independently and ignore the ones that don't
        // apply to this API level.
        listOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.CAMERA",
        ).forEach { permission ->
            runCatching { uiAutomation.grantRuntimePermission(packageName, permission) }
        }

        // Programmatically delete devices.json to ensure a fresh test start
        val devicesFile = context.getFileStreamPath("devices.json")
        if (devicesFile.exists()) {
            val deleted = devicesFile.delete()
            println("Deleted existing devices.json for a clean test: $deleted")
        }

        // Start from home screen
        device.pressHome()

        // Wait for launcher to settle
        val launcherPackage: String? = device.launcherPackageName
        if (launcherPackage != null) {
            device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), 5000)
        }

        // Launch the application. Name MainActivity outright rather than asking for "the" launch
        // intent of the package: a debug build carries a SECOND launcher entry, the DemoActivity
        // trampoline, which restarts MainActivity with the fake-board extra and serves the whole
        // flow from FakeBoard. getLaunchIntentForPackage picks the first of the two, so it can hand
        // back the simulated board — and this test, plus the tools/ble-validation harness built on
        // it, exists to measure the real radio against a real board.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Wait for the application to load. Cold-starting the Compose app on a low-end device
        // (e.g. a Fire tablet) can take ~16s, so allow generous headroom here.
        assertTrue("App failed to launch", device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 30000))
    }

    @Test
    fun testRealWorldConnectivity() {
        // 1. Verify we are on the Devices screen
        assertTrue("Devices screen title not found", device.wait(Until.hasObject(By.text("Devices")), 5000))

        // 2. Click "Start Scanning" button
        val startScanBtn = device.wait(Until.findObject(By.text("Start Scanning")), 5000)
        assertNotNull("Start Scanning button not found", startScanBtn)
        startScanBtn.click()

        // 3. Wait for the physical board to be discovered
        // We scan for up to 15 seconds to give the BLE scanner enough time to detect the board.
        // A board that is not saved shows the default name "SnapshotPB" (device_default_name),
        // not its advertised name "Snapshot PB", so the pattern accepts both.
        println("Scanning for BLE device (which shows up as 'SnapshotPB')...")
        val targetDeviceText = Pattern.compile("Snapshot ?PB")
        val discoveredDevice = device.wait(Until.findObject(By.text(targetDeviceText)), 15000)
        assertNotNull("Physical device (SnapshotPB) was not discovered in 15 seconds", discoveredDevice)
        println("Device found, connecting...")

        // 4. Click the discovered device to connect
        discoveredDevice.click()

        // 5. Wait for the app to connect and read OTS characteristics
        // Once completed, it should show the "Finish Configuration" button. The wait is long because
        // the app tries the connect again after a rare connect failure. A recovered connect can take
        // approximately 15 s more.
        val finishConfigBtn = device.wait(Until.findObject(By.text("Finish Configuration")), 45000)
        assertNotNull("Finish Configuration button did not appear after 45 seconds", finishConfigBtn)
        println("Connected and read OTS characteristics successfully. Clicking Finish Configuration...")

        // 6. Click "Finish Configuration" button to confirm the device and go to Configure screen
        finishConfigBtn.click()

        // 7. Verify we navigate to the Configure Device screen
        assertTrue(
            "Configure Device screen did not appear",
            device.wait(Until.hasObject(By.text("Configure Device")), 10000)
        )

        // 8. Enter a custom device name and boot text.
        // There are two edit text fields on this screen: Device Name (index 0) and Boot Text
        // (index 1). Both fields cap their input — Device.MAX_NAME_LENGTH and
        // MAX_BOOT_TEXT_LENGTH, 10 characters each — so a longer literal is silently truncated and
        // the rest of the test then works on a string nobody wrote. Keep both within the cap, and
        // assert the field kept what was typed so a future cap change fails here rather than
        // quietly changing what this test covers.
        val editTextFields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)
        assertTrue("Expected at least two EditText fields on Configure Device screen", editTextFields.size >= 2)

        // The boot text is the board setting that makes the "Unsaved Changes" banner show. The
        // board keeps the text of the last run, so a fixed text makes no change on the next run
        // and the banner does not show. Thus each run writes a text that is different from the
        // text on the board, and the end of the test writes the original text back. Read the
        // original text before a set replaces the nodes. The draft of the screen fills after the
        // first frame, so wait for the text: a filled field is never empty, because the app shows
        // the default text "SnapshotPB" for an empty board text.
        val originalBootText = awaitNonEmptyFieldText(index = 1)
            ?: throw AssertionError("Boot text field did not show the boot text of the board")
        val bootText = perRunBootText(previous = originalBootText)

        val deviceName = "SnapPB E2E"
        val nameField = editTextFields[0]
        nameField.text = deviceName
        assertEquals(
            "Device name field did not keep the typed name",
            deviceName,
            awaitFieldText(index = 0, expected = deviceName)
        )

        // 9 to 13. Save the settings locally, then write them to the board.
        saveBootTextToBoard(bootText)

        // 14. Write the original boot text back, as OtsSessionE2ETest does. The card title is not
        // a control: the "Configure" button on the Device Configuration card opens the screen.
        val configureBtn = findWithScroll(By.text("Configure"))
        assertNotNull("Configure button not found on the Device Configuration card", configureBtn)
        configureBtn!!.click()
        assertTrue(
            "Configure Device screen did not appear for the restore",
            device.wait(Until.hasObject(By.text("Configure Device")), 10000)
        )
        saveBootTextToBoard(originalBootText)

        // Wait a few seconds for the asynchronous BLE write to finish before the test terminates the app
        Thread.sleep(3000)
        println("E2E Test completed successfully: device connected, configured, and write persisted!")
    }

    /**
     * Types [bootText] on the Configure Device screen, saves it locally, and writes it to the
     * board with "Save to Board". The Configure Device screen must be on show, and [bootText]
     * must be different from the boot text on the board.
     */
    private fun saveBootTextToBoard(bootText: String) {
        val editTextFields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5000)
        assertTrue("Expected at least two EditText fields on Configure Device screen", editTextFields.size >= 2)
        editTextFields[1].text = bootText
        assertEquals(
            "Boot text field did not keep the typed text",
            bootText,
            awaitFieldText(index = 1, expected = bootText)
        )

        // 9. Click the "Save" button to save settings locally and return to Profiles screen.
        // The Configure Device screen is taller than a phone viewport, so Save sits below the fold.
        assertNotNull("Save button not found on Configure Device screen", findWithScroll(By.text("Save")))
        // A tap on Save after the accessibility scroll was lost on the Nexus 5X and the Pixel
        // (2026-10-01), so use the accessibility click. The cause of the lost tap is not known, and
        // it can be an app defect, so this click does not prove that a real tap on Save works.
        assertTrue("Save button could not be clicked", clickByAccessibility("Save"))

        // 10. Verify we navigate back to the Profiles screen containing the Device Configuration card
        assertTrue(
            "Failed to return to the Profiles screen after saving",
            device.wait(Until.hasObject(By.text("Device Configuration")), 10000)
        )

        // 11. Verify the "Unsaved Changes" banner is visible
        assertTrue(
            "Unsaved Changes banner did not appear for boot text '$bootText'",
            device.wait(Until.hasObject(By.text("Unsaved Changes")), 5000)
        )

        // 12. Click the "Save to Board" button
        val saveToBoardBtn = device.wait(Until.findObject(By.text("Save to Board")), 5000)
        assertNotNull("Save to Board button not found", saveToBoardBtn)
        saveToBoardBtn.click()

        // 13. Verify the "Unsaved Changes" banner disappears (meaning the board successfully persisted the changes)
        assertTrue(
            "Unsaved Changes banner did not disappear after saving boot text '$bootText' to board",
            device.wait(Until.gone(By.text("Unsaved Changes")), 15000)
        )
    }

    /**
     * Returns a boot text for this run that is different from [previous].
     *
     * The text holds only capital letters and digits, which the font pack of the board draws, and
     * it has 9 characters, in the 10-byte limit of `DeviceConfig.boot_text`
     * (`Device.MAX_BOOT_TEXT_LENGTH`). The digits come from the clock, so two runs almost always
     * get different texts. If the text is the same as [previous], a different prefix makes it
     * different.
     */
    private fun perRunBootText(previous: String): String {
        val stamp = (System.currentTimeMillis() % 1_000_000).toString().padStart(6, '0')
        val text = "E2E$stamp"
        return if (text != previous) text else "E2F$stamp"
    }

    /**
     * Polls the text-entry field at [index] until it reads [expected], and returns what it read
     * last, so a mismatch reports the value the field actually holds.
     *
     * Two things make a plain read unreliable. `setText` returns before Compose has recomposed, so
     * reading straight back still reports the text from before the set; and the recomposition
     * replaces the node, which leaves a [UiObject2] captured earlier pointing at the old one. So
     * re-query the fields on every poll rather than holding on to one.
     */
    private fun awaitFieldText(index: Int, expected: String, timeoutMs: Long = 5000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen: String? = null
        while (System.currentTimeMillis() < deadline) {
            seen = device.findObjects(By.clazz("android.widget.EditText")).getOrNull(index)?.text
            if (seen == expected) return seen
            Thread.sleep(100)
        }
        return seen
    }

    /**
     * Polls the text-entry field at [index] until it holds text, and returns that text, or null
     * when the field stayed empty for [timeoutMs].
     */
    private fun awaitNonEmptyFieldText(index: Int, timeoutMs: Long = 5000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val seen = device.findObjects(By.clazz("android.widget.EditText")).getOrNull(index)?.text
            if (!seen.isNullOrEmpty()) return seen
            Thread.sleep(100)
        }
        return null
    }

    /**
     * Waits for an object that matches [selector], scrolling the screen down when it is not already
     * on show, and returns it, or null when none appeared.
     *
     * UiAutomator does not find a node that is not on the screen. A screen taller than the
     * viewport — Configure Device on a phone — therefore needs a scroll first.
     *
     * The scroll is the accessibility scroll action, not a swipe. On Configure Device, a swipe
     * scroll (`scrollUntil`) stopped before the Save button (measured 2026-10-01). The cause is
     * not known.
     */
    private fun findWithScroll(selector: BySelector, timeoutMs: Long = 5000): UiObject2? {
        device.wait(Until.findObject(selector), timeoutMs)?.let { return it }
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(MAX_SCROLLS) {
            val scrollable = uiAutomation.rootInActiveWindow?.let(::findScrollable) ?: return null
            val moved = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            device.wait(Until.findObject(selector), 1000)?.let { return it }
            if (!moved) return null
        }
        return null
    }

    /**
     * Clicks the control that shows exactly [text] with the accessibility click action, and returns
     * whether the control took the click. A touch on a screen that [findWithScroll] scrolled can be
     * lost: the screen stayed on show for 8 s after such a tap, with no change.
     */
    private fun clickByAccessibility(text: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        var node = findNode(root) { it.text?.toString() == text && it.isVisibleToUser && it.isEnabled }
            ?: return false
        while (!node.isClickable) node = node.parent ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** The first node under [root], in breadth-first order, that can scroll forward. */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = findNode(root) { node ->
        node.isScrollable && AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD in node.actionList
    }

    /**
     * The first node under [root], in breadth-first order, that [accept] takes. Compose does not
     * answer `findAccessibilityNodeInfosByText`, so the search walks the tree.
     */
    private fun findNode(root: AccessibilityNodeInfo, accept: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (accept(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private companion object {
        /** More scroll steps than the tallest screen needs, so a stuck scroll still ends. */
        const val MAX_SCROLLS = 20
    }
}
