package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.bluetooth.ots.toProtocolDeviceConfig
import snapshotpb.v1.BluetoothAtBoot
import snapshotpb.v1.BoltMode
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.DisplayWindow
import snapshotpb.v1.Language
import snapshotpb.v1.ScreenBrightness
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cover for the simulated board seed and its merge-on-zero write rule. */
class FakeBoardConfigTest {

    @Test
    fun seed_holdsBluetoothAtBootOff() {
        assertEquals(
            BluetoothAtBoot.BLUETOOTH_AT_BOOT_OFF,
            toProtocolDeviceConfig(fakeBoardSeed()).bluetoothAtBoot,
        )
    }

    @Test
    fun withWrittenSettings_takesAWrittenBluetoothAtBoot() {
        val board = DeviceConfig(bluetoothAtBoot = BluetoothAtBoot.BLUETOOTH_AT_BOOT_OFF)
        val written = DeviceConfig(bluetoothAtBoot = BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE)

        assertEquals(
            BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE,
            board.withWrittenSettings(written).bluetoothAtBoot,
        )
    }

    @Test
    fun withWrittenSettings_keepsTheBoardBluetoothAtBootForUnspecified() {
        val board = DeviceConfig(bluetoothAtBoot = BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE)
        val written = DeviceConfig(bluetoothAtBoot = BluetoothAtBoot.BLUETOOTH_AT_BOOT_UNSPECIFIED)

        assertEquals(
            BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE,
            board.withWrittenSettings(written).bluetoothAtBoot,
        )
    }

    @Test
    fun withWrittenSettings_keepsTheOtherUnsetSettings() {
        val window = DisplayWindow(x = 0u, y = 0u, w = 128u, h = 32u)
        val board = DeviceConfig(
            activeProfile = 1u,
            bootText = "BOARD",
            displayWindow = window,
            language = Language.LANGUAGE_DE,
            screenBrightness = ScreenBrightness.SCREEN_BRIGHTNESS_HIGH,
            screenOffDelay = 30u,
            boltMode = BoltMode.BOLT_MODE_OPEN,
            bluetoothAtBoot = BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE,
        )
        val written = DeviceConfig(
            activeProfile = 2u,
            bootText = "NEW",
        )

        val updated = board.withWrittenSettings(written)

        assertEquals(2u, updated.activeProfile)
        assertEquals("NEW", updated.bootText)
        assertEquals(window, updated.displayWindow)
        assertEquals(Language.LANGUAGE_DE, updated.language)
        assertEquals(ScreenBrightness.SCREEN_BRIGHTNESS_HIGH, updated.screenBrightness)
        assertEquals(30u, updated.screenOffDelay)
        assertEquals(BoltMode.BOLT_MODE_OPEN, updated.boltMode)
        assertEquals(BluetoothAtBoot.BLUETOOTH_AT_BOOT_RESTORE, updated.bluetoothAtBoot)
    }
}
