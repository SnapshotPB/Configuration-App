package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class BluetoothRequestManager {
    private val _bus = MutableSharedFlow<BluetoothRequest>()
    val bus: SharedFlow<BluetoothRequest> = _bus

    /**
     * The number of collectors of [bus]. The bus has no buffer, so [send] with no collector loses
     * the request. A sender that must not lose a request waits until this is above zero.
     */
    val subscriptionCount: StateFlow<Int> get() = _bus.subscriptionCount

    suspend fun send(bluetoothRequest: BluetoothRequest) =
        _bus.emit(bluetoothRequest)
}
