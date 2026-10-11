package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegateImpl
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegateImpl
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.model.newDeviceName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cover for issue #156. Finish Configuration stored the advertised name "Snapshot PB", which is
 * longer than the name limit, and a Save on the Configure Device screen then stored "Snapshot P".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewDeviceNameSaveTest {

    @Test
    fun aNewDeviceKeepsItsNameThroughASaveOnTheConfigureScreen() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = testDeviceStore(dispatcher)
        val readBus = DeviceReadResultBus()
        val syncDelegate = DeviceSyncDelegateImpl(
            deviceStore = store,
            deviceReadResultBus = readBus,
            bluetoothRequestManager = BluetoothRequestManager(),
            dispatcher = dispatcher,
            photoStamp = { 0L },
        )
        val viewModelDelegate = DeviceViewModelDelegateImpl(
            deviceStore = store,
            bluetoothRequestManager = BluetoothRequestManager(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            dispatcher = dispatcher,
            photoStamp = { 0L },
        )
        testScheduler.advanceUntilIdle()

        // The first read of a new board. The engine gives the device the advertised name.
        readBus.emit(DeviceReadResult(testDevice(name = BOARD_ADVERTISED_NAME)))
        testScheduler.advanceUntilIdle()
        val pending = assertIs<PendingSync.NewDevice>(syncDelegate.pendingSyncs.value[testDeviceId])

        // Finish Configuration, as ConnectingDeviceScreen does it.
        syncDelegate.confirmNewDevice(testDeviceId, newDeviceName(pending.device.name, DEFAULT_NAME), null)
        testScheduler.advanceUntilIdle()
        assertEquals(DEFAULT_NAME, store.knownDevices.value.single().name)

        // The user changes only the boot text on the Configure Device screen, then saves.
        val viewModel = ConfigureDeviceViewModel(testDeviceId.toString(), store, viewModelDelegate)
        viewModel.onDeviceLoaded(store.knownDevices.value.single())
        viewModel.editDraft { it.copy(bootText = "SnapTest!") }
        viewModel.saveDraft()
        testScheduler.advanceUntilIdle()

        val saved = store.knownDevices.value.single()
        assertEquals(DEFAULT_NAME, saved.name, "a Save must not rename a new device")
        assertEquals("SnapTest!", saved.bootText)
    }

    private companion object {
        /** The English value of `device_default_name`. `DefaultDeviceNameTest` checks all locales. */
        const val DEFAULT_NAME = "SnapshotPB"
    }
}
