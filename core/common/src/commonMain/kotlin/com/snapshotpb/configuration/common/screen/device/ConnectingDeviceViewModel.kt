package com.snapshotpb.configuration.common.screen.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.resolveBoardId
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegate
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ConnectingDeviceViewModel(
    val deviceId: String,
    private val deviceSyncDelegate: DeviceSyncDelegate,
    private val bluetoothStateManager: BluetoothStateManager,
    deviceStore: DeviceStore,
    deviceViewModelDelegate: DeviceViewModelDelegate,
) : ViewModel(), DeviceViewModelDelegate by deviceViewModelDelegate {

    /**
     * The [Device.id] of the board under connection. A board that the scan found opens this screen
     * with its scan key, and the read gives its board `device_id` (#127). The pending sync, the
     * stored device and the next screen all use the board id, so this follows the id that the read
     * records.
     */
    val boardId: StateFlow<Uuid> = bluetoothStateManager.boardIds
        .map { ids -> Uuid.parse(resolveBoardId(ids, deviceId)) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            Uuid.parse(resolveBoardId(bluetoothStateManager.boardIds.value, deviceId)),
        )

    val pendingSyncs: StateFlow<Map<Uuid, PendingSync>> = deviceSyncDelegate.pendingSyncs
    val bluetoothState: StateFlow<BluetoothState> = bluetoothStateManager.bluetoothState
    val connectionProgress: StateFlow<ConnectionProgress> = bluetoothStateManager.connectionProgress
    val knownDevices: StateFlow<List<Device>> = deviceStore.knownDevices

    val knownDevice: Flow<Device?> =
        knownDevices.combine(boardId) { devices, id -> devices.firstOrNull { it.id == id } }

    fun startConnect() {
        // Clear the terminal progress of an earlier connection (to any device) before the engine
        // gets to its own reset, or the screen reads a completed step 4 as "this device is done".
        bluetoothStateManager.resetProgress()
        viewModelScope.launch {
            connect(deviceId)
        }
    }

    fun confirmNewDevice(name: String, photo: Photo?) {
        viewModelScope.launch {
            deviceSyncDelegate.confirmNewDevice(boardId.value, name, photo)
        }
    }

    fun dismissNewDevice() {
        viewModelScope.launch {
            deviceSyncDelegate.dismissNewDevice(boardId.value)
        }
    }
}
