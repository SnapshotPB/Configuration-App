package com.snapshotpb.configuration.common.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** High-level connection state surfaced by the Profiles screen connection card. */
enum class ConnectionUiState { Disconnected, Connecting, Connected, Failed }

class ProfilesScreenViewModel(
    private val deviceId: String,
    private val deviceStore: DeviceStore,
    private val bluetoothStateManager: BluetoothStateManager,
    deviceViewModelDelegate: DeviceViewModelDelegate
) : ViewModel(), DeviceViewModelDelegate by deviceViewModelDelegate {

    val isConnected: Flow<Boolean> =
        bluetoothStateManager.connectedDevices.map { it.contains(deviceId) }

    val connectionProgress: StateFlow<ConnectionProgress> = bluetoothStateManager.connectionProgress

    /** True between the user pressing Connect/Retry and the next terminal result. */
    private val attempted = MutableStateFlow(false)

    val connectionUiState: StateFlow<ConnectionUiState> = combine(
        bluetoothStateManager.connectedDevices,
        bluetoothStateManager.bluetoothState,
        bluetoothStateManager.connectionProgress,
        attempted,
    ) { connected, btState, progress, didAttempt ->
        when {
            connected.contains(deviceId) -> ConnectionUiState.Connected
            (btState as? BluetoothState.Connecting)?.deviceId == deviceId -> ConnectionUiState.Connecting
            didAttempt && progress.isFailed -> ConnectionUiState.Failed
            didAttempt && !progress.isCompleted -> ConnectionUiState.Connecting
            else -> ConnectionUiState.Disconnected
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConnectionUiState.Disconnected)

    fun requestConnect() {
        attempted.value = true
        // Clear any stale terminal progress (from a prior attempt or another device) so the
        // card shows Connecting immediately instead of flashing the previous Failed/Completed.
        bluetoothStateManager.resetProgress()
        viewModelScope.launch { connect(deviceId) }
    }

    fun requestDisconnect() {
        attempted.value = false
        viewModelScope.launch { disconnect(deviceId) }
    }

    /**
     * The id that this device moved to, or `null` while the store still holds [deviceId].
     *
     * A device that an older build stored under the scan key of its board moves to the board
     * `device_id` on the first read (#127). A connect from this screen can do that read, so the
     * screen follows the device to its new id.
     */
    val movedToDeviceId: Flow<String?> = combine(
        bluetoothStateManager.boardIds,
        deviceStore.knownDevices,
    ) { boardIds, devices ->
        val ids = devices.mapTo(mutableSetOf()) { it.id.toString() }
        boardIds[deviceId]?.takeIf { boardId -> deviceId !in ids && boardId in ids }
    }

    fun deviceFlow(): Flow<Device?> =
        deviceStore.knownDevices.map { devices -> devices.firstOrNull { it.id.toString() == deviceId } }

    /**
     * Deletes [profile] from this device. The delete does nothing when the device has only
     * [Device.MIN_PROFILE_COUNT] profiles, because the board refuses a write with no profiles (see
     * [Device.canDeleteProfile]). The screen hides the delete action in that case, and this guard
     * also covers a delete that a sync makes stale.
     */
    fun deleteProfile(profile: MarkerProfile) {
        deviceStore.update { current ->
            current.map { device ->
                if (device.id.toString() == deviceId && device.canDeleteProfile()) {
                    // Every later profile moves down one slot, so its id must follow (see
                    // withSlotProfileIds). A deleted active profile leaves no active profile.
                    device.copy(
                        knownProfiles = device.knownProfiles.filter { it.id != profile.id },
                        activeProfileId = device.activeProfileId?.takeIf { it != profile.id },
                    ).withSlotProfileIds()
                } else {
                    device
                }
            }
        }
    }

    private companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
