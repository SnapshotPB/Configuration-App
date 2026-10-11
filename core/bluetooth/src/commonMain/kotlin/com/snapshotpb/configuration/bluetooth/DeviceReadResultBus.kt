package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Carries the freshly-decoded domain [Device] from a successful OTS read out of
 * BluetoothManager. A separate bus (rather than a write into DeviceStore) keeps
 * "did we just see this device for the first time / does it conflict with what's
 * stored locally" a UX concern, not a Bluetooth-layer concern.
 */
class DeviceReadResultBus {
    private val _results = MutableSharedFlow<DeviceReadResult>(extraBufferCapacity = 8)
    val results: SharedFlow<DeviceReadResult> = _results

    suspend fun emit(result: DeviceReadResult) = _results.emit(result)
}

data class DeviceReadResult(
    val device: Device,
    /**
     * The scan key of the peripheral that gave this read, or `null` when there is none. Older
     * builds stored a board under an id made from this key, so the sync delegate uses it to find
     * that device and move it to [Device.id], the board `device_id` (see [movedToBoardId]).
     */
    val scanKey: String? = null,
)
