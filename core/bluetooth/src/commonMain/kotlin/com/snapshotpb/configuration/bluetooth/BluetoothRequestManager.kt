package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class BluetoothRequestManager {
    private val _bus = MutableSharedFlow<BluetoothRequest>()
    val bus: SharedFlow<BluetoothRequest> = _bus

    suspend fun send(bluetoothRequest: BluetoothRequest) =
        _bus.emit(bluetoothRequest)
}
