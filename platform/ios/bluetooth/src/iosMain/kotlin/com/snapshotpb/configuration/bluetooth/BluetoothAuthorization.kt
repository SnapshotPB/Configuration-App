package com.snapshotpb.configuration.bluetooth

import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

/** Coarse CoreBluetooth authorization status, collapsing the CoreBluetooth cases we act on. */
enum class BluetoothAuthorization { NOT_DETERMINED, DENIED, AUTHORIZED }

/**
 * Reads the app's current CoreBluetooth authorization via the static [CBCentralManager.authorization].
 * This does NOT instantiate a [CBCentralManager] and never shows the permission prompt, so it is safe
 * to call at launch to decide whether to bring Bluetooth up eagerly (already authorized), defer until
 * the user opts in (not determined), or surface a permission-denied state (denied/restricted).
 */
fun currentBluetoothAuthorization(): BluetoothAuthorization =
    when (CBCentralManager.authorization) {
        CBManagerAuthorizationAllowedAlways -> BluetoothAuthorization.AUTHORIZED
        CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> BluetoothAuthorization.DENIED
        else -> BluetoothAuthorization.NOT_DETERMINED
    }
