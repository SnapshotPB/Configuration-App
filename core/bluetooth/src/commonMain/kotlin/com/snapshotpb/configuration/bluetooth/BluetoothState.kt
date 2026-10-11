package com.snapshotpb.configuration.bluetooth

sealed interface BluetoothState {
    object NeedsPermission : BluetoothState
    object Ready : BluetoothState
    object Scanning : BluetoothState
    object Disabled : BluetoothState

    /**
     * The OS has explicitly denied (or restricts) Bluetooth permission, as opposed to the radio
     * merely being off ([Disabled]). Recoverable only by the user re-enabling access in system
     * settings, so the UI offers a deep-link there rather than a "grant" prompt that won't reappear.
     */
    object PermissionDenied : BluetoothState

    /**
     * BLE scanning is blocked because the system Location toggle is off. On Android API <= 30 the
     * scanner silently returns no results while Location services are disabled, even with the radio
     * on and permission granted. Surfaced only on pre-S Android (from API 31 `BLUETOOTH_SCAN`
     * declares `neverForLocation`, so scanning no longer depends on Location); the UI deep-links to
     * system Location settings.
     */
    object LocationDisabled : BluetoothState
    data class Connecting(val deviceId: String) : BluetoothState
}