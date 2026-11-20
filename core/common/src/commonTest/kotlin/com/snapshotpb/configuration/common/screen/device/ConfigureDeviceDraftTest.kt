package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegateImpl
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.model.BoardBluetoothAtBoot
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.MarkerBoltMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cover for how the configuration draft takes its values from the stored device. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureDeviceDraftTest {

    @Test
    fun hydratedFrom_nullKeepsTheDraftEmpty() {
        val draft = ConfigureDeviceDraft().hydratedFrom(null)

        assertFalse(draft.hydrated, "Save stays disabled until the store has the device")
    }

    @Test
    fun hydratedFrom_theFirstDeviceFillsTheWholeDraft() {
        val device = testDevice(
            name = "Marker",
            bootText = "Hello",
            language = BoardLanguage.DE,
            screenOffDelay = 30,
            boltMode = MarkerBoltMode.OPEN,
            bluetoothAtBoot = BoardBluetoothAtBoot.RESTORE,
        )

        val draft = ConfigureDeviceDraft().hydratedFrom(device)

        assertTrue(draft.hydrated)
        assertEquals("Marker", draft.deviceName)
        assertEquals("Hello", draft.bootText)
        assertEquals(BoardLanguage.DE, draft.language)
        assertEquals(30, draft.screenOffDelay)
        assertEquals(MarkerBoltMode.OPEN, draft.boltMode)
        assertEquals(BoardBluetoothAtBoot.RESTORE, draft.bluetoothAtBoot)
    }

    @Test
    fun hydratedFrom_aLaterDeviceFillsOnlyEmptyBoardSettings() {
        // The user renamed the device and chose a language. Then the board read finished and
        // reported its own language, a brightness, a bolt mode, and Bluetooth at boot.
        val edited = ConfigureDeviceDraft()
            .hydratedFrom(testDevice(name = "Marker"))
            .copy(deviceName = "Renamed", language = BoardLanguage.FR)

        val draft = edited.hydratedFrom(
            testDevice(
                name = "Marker",
                language = BoardLanguage.EN,
                screenBrightness = BoardScreenBrightness.HIGH,
                boltMode = MarkerBoltMode.OPEN,
                bluetoothAtBoot = BoardBluetoothAtBoot.RESTORE,
            )
        )

        assertEquals("Renamed", draft.deviceName, "an edit is never replaced")
        assertEquals(BoardLanguage.FR, draft.language, "a choice is never replaced")
        assertEquals(BoardScreenBrightness.HIGH, draft.screenBrightness, "an empty setting fills")
        assertEquals(MarkerBoltMode.OPEN, draft.boltMode, "an empty bolt mode fills")
        assertEquals(BoardBluetoothAtBoot.RESTORE, draft.bluetoothAtBoot, "an empty Bluetooth at boot fills")
    }

    @Test
    fun hydratedFrom_aLaterDeviceKeepsABluetoothAtBootChoiceMadeOnTheScreen() {
        val edited = ConfigureDeviceDraft()
            .hydratedFrom(testDevice(name = "Marker", bluetoothAtBoot = BoardBluetoothAtBoot.OFF))
            .copy(bluetoothAtBoot = BoardBluetoothAtBoot.RESTORE)

        val draft = edited.hydratedFrom(testDevice(name = "Marker", bluetoothAtBoot = BoardBluetoothAtBoot.OFF))

        assertEquals(BoardBluetoothAtBoot.RESTORE, draft.bluetoothAtBoot)
    }

    @Test
    fun hydratedFrom_aDeviceWithNoBluetoothAtBootKeepsTheDraftNull() {
        val draft = ConfigureDeviceDraft().hydratedFrom(testDevice(name = "Marker", bluetoothAtBoot = null))

        assertTrue(draft.hydrated)
        assertNull(draft.bluetoothAtBoot)
    }

    @Test
    fun saveDraft_passesBluetoothAtBootToTheStore() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = testDeviceStore(
            dispatcher = dispatcher,
            devices = listOf(testDevice(bluetoothAtBoot = BoardBluetoothAtBoot.OFF)),
        )
        val delegate = DeviceViewModelDelegateImpl(
            deviceStore = store,
            bluetoothRequestManager = BluetoothRequestManager(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            dispatcher = dispatcher,
            photoStamp = { 0L },
        )
        testScheduler.advanceUntilIdle()

        val viewModel = ConfigureDeviceViewModel(testDeviceId.toString(), store, delegate)
        viewModel.onDeviceLoaded(store.knownDevices.value.single())
        viewModel.editDraft { it.copy(bluetoothAtBoot = BoardBluetoothAtBoot.RESTORE) }
        viewModel.saveDraft()
        testScheduler.advanceUntilIdle()

        assertEquals(BoardBluetoothAtBoot.RESTORE, store.knownDevices.value.single().bluetoothAtBoot)
    }
}
