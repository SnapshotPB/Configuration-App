package com.snapshotpb.configuration

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.snapshotpb.configuration.common.util.formatTenths
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.screen.CONNECTING_CONTINUE_TAG
import com.snapshotpb.configuration.screen.DEVICE_CONFIG_ACTIVE_PROFILE_TAG
import com.snapshotpb.configuration.screen.DEVICE_CONFIG_TUNE_PROFILE_TAG
import com.snapshotpb.configuration.screen.DEVICE_LIST_HEADER_TAG
import com.snapshotpb.configuration.screen.DEVICE_LIST_SCAN_TAG
import com.snapshotpb.configuration.screen.DISCOVERED_DEVICE_TAG_PREFIX
import com.snapshotpb.configuration.screen.PROFILE_FIELD_CHIP_TAG_PREFIX
import com.snapshotpb.configuration.screen.PROFILE_FIELD_PLUS_TAG
import com.snapshotpb.configuration.screen.PROFILE_FIELD_VALUE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.appJson
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class WearConnectivityE2ETest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val packageName = "com.snapshotpb.configuration.wear"

    @Before
    fun setUp() {
        // Initialize UiDevice
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Grant permissions programmatically
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.uiAutomation
        context = instrumentation.targetContext

        uiAutomation.grantRuntimePermission(packageName, "android.permission.BLUETOOTH_CONNECT")
        uiAutomation.grantRuntimePermission(packageName, "android.permission.BLUETOOTH_SCAN")
        uiAutomation.grantRuntimePermission(packageName, "android.permission.ACCESS_FINE_LOCATION")

        // Programmatically delete devices.json to ensure a fresh test start
        val devicesFile = context.getFileStreamPath("devices.json")
        if (devicesFile.exists()) {
            val deleted = devicesFile.delete()
            println("Deleted existing devices.json for a clean test: $deleted")
        }

        // Every test runs in the one app process, so the store singleton keeps the devices of
        // the test before. Empty it too, or the device list shows a known device above the Scan
        // button and pushes the button below the fold.
        val di = (context.applicationContext as DIAware).di
        di.direct.instance<DeviceStore>().update(emptyList())

        // Start from home screen
        device.pressHome()

        // Launch the application
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("App launch intent not found", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        // The extra turns on the demo board of the debug build. It also keeps the watch-to-phone
        // sync off, so the demo board does not reach a paired phone.
        intent.putExtra(MainActivity.EXTRA_MOCK_DEVICES, true)
        context.startActivity(intent)

        // Wait for the application to load
        assertTrue("App failed to launch", device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5000))
    }

    @Test
    fun testWearConnectivity() {
        // Scan, connect to the demo board, and land on the device config screen.
        connectMockDevice()
        println("Watch E2E Test completed successfully!")
    }

    @Test
    fun testProfileFieldStepCommitsToStore() {
        connectMockDevice()

        // 1. Open the editor of the active profile.
        val tuneCard = device.wait(Until.findObject(By.res(DEVICE_CONFIG_TUNE_PROFILE_TAG)), 5000)
        assertNotNull("Tune Profile card not found", tuneCard)
        tuneCard.click()
        assertTrue(
            "Profile editor did not show the active profile name",
            device.wait(Until.hasObject(By.text("Semi Auto")), 5000)
        )

        // 2. Open the stepper of the sear dwell. The chip sits below the mode chips, so scroll to it.
        val dwellField = MarkerProfileField.SEAR_SOLENOID_DWELL
        val dwellChip = findWithScroll(By.res(PROFILE_FIELD_CHIP_TAG_PREFIX + dwellField.name))
        assertNotNull("Sear dwell chip not found", dwellChip)
        dwellChip!!.click()

        // 3. Step the value up once at the fine step.
        val plusButton = device.wait(Until.findObject(By.res(PROFILE_FIELD_PLUS_TAG)), 5000)
        assertNotNull("Plus button not found", plusButton)
        plusButton.click()

        // 4. The screen shows the new value at once. The mock profile keeps the model default, so
        // a fresh profile gives the start value in tenths of a millisecond.
        val expected = MarkerProfile(id = Uuid.random(), name = "probe").searSolenoidDwell + 1
        val expectedLabel = "${formatTenths(expected)} ms"
        assertTrue(
            "Stepper did not show the stepped value",
            device.wait(Until.hasObject(By.res(PROFILE_FIELD_VALUE_TAG).text(expectedLabel)), 3000)
        )

        // 5. The stepper commits a short time after the tap. Wait for the store file to hold it.
        val stored = waitForStoredValue(dwellField, 5000)
        assertEquals("Store did not receive the stepped sear dwell", expected, stored)
        println("Watch stepper E2E Test completed successfully!")
    }

    /**
     * Scans for the mock board, connects, and lands on the device config screen.
     *
     * Every lookup uses a test tag, not a text, so the test runs on a watch in any language.
     */
    private fun connectMockDevice() {
        assertTrue(
            "Devices screen header not found",
            device.wait(Until.hasObject(By.res(DEVICE_LIST_HEADER_TAG)), 5000)
        )
        val scanBtn = device.wait(Until.findObject(By.res(DEVICE_LIST_SCAN_TAG)), 5000)
        assertNotNull("Scan button not found", scanBtn)
        scanBtn.click()
        // Wait for the scan to start, then scroll the discovered boards into view.
        Thread.sleep(2000)
        device.swipe(228, 350, 228, 100, 20)
        Thread.sleep(500)
        val discoveredDevice = device.wait(
            Until.findObject(By.res(DISCOVERED_DEVICE_TAG_PREFIX + MOCK_DEVICE_ID)),
            15000
        )
        assertNotNull("Mock device was not discovered", discoveredDevice)
        discoveredDevice.click()
        val continueBtn = clickWhenSettled(By.res(CONNECTING_CONTINUE_TAG), 20000)
        assertNotNull("Continue button did not appear within 20 seconds", continueBtn)
        assertTrue(
            "Failed to reach the Device Config screen with the Active Profile card",
            device.wait(Until.hasObject(By.res(DEVICE_CONFIG_ACTIVE_PROFILE_TAG)), 10000)
        )
    }

    /**
     * Waits for an object that matches [selector], lets the screen settle, then finds it again and
     * clicks it. Returns the clicked object, or null when none appeared within [timeoutMs].
     *
     * The Continue chip appears while the connecting list scrolls to it. A click on the first
     * match lands on the bounds from before the scroll, so the tap misses the chip.
     */
    private fun clickWhenSettled(selector: BySelector, timeoutMs: Long): UiObject2? {
        device.wait(Until.findObject(selector), timeoutMs) ?: return null
        device.waitForIdle()
        val settled = device.wait(Until.findObject(selector), 2000) ?: return null
        settled.click()
        return settled
    }

    /**
     * Scrolls the list down until an object that matches [selector] is on screen.
     *
     * A fast swipe flings the list past several chips before the next check, so this uses the
     * controlled scroll of the list node, which moves a fraction of the list at a time.
     */
    private fun findWithScroll(selector: BySelector): UiObject2? {
        device.findObject(selector)?.let { return it }
        val list = device.wait(Until.findObject(By.scrollable(true)), 2000) ?: return null
        return list.scrollUntil(Direction.DOWN, Until.findObject(selector))
    }

    /**
     * Polls the store file until the active profile of the mock device holds a value of [field]
     * that differs from the model default, and returns that value, or null after [timeoutMs].
     */
    private fun waitForStoredValue(field: MarkerProfileField, timeoutMs: Long): Int? {
        val defaultValue = field.get(MarkerProfile(id = Uuid.random(), name = "probe"))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val file = context.getFileStreamPath("devices.json")
            if (file.exists()) {
                val devices = appJson.decodeFromString<List<Device>>(file.readText())
                val device = devices.firstOrNull { it.id.toString() == MOCK_DEVICE_ID }
                val profile = device?.knownProfiles?.firstOrNull { it.id == device.activeProfileId }
                val stored = profile?.let(field::get)
                if (stored != null && stored != defaultValue) return stored
            }
            Thread.sleep(250)
        }
        return null
    }

    private companion object {
        /** The id of the mock board that the MOCK_DEVICES intent extra makes visible. */
        const val MOCK_DEVICE_ID = "00000000-0000-0000-0000-000000001234"
    }
}
