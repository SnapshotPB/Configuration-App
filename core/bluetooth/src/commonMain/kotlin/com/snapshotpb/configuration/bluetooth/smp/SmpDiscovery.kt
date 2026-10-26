package com.snapshotpb.configuration.bluetooth.smp

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService

// Cheap checks on the discovered services. They do no GATT operation. A board on a firmware with no
// update service has no SMP service, so the engine can skip the SMP steps with no error. The
// services must be discovered first.

/** The SMP service in the discovered services of [peripheral], or null when the board has none. */
fun findSmpService(peripheral: BluetoothPeripheral): BluetoothService? =
    peripheral.services.firstOrNull { it.uuid == SmpUuids.Service }

/** True when the discovered services of [peripheral] have the SMP service. */
fun hasSmpService(peripheral: BluetoothPeripheral): Boolean = findSmpService(peripheral) != null

/**
 * The SMP characteristic in the discovered services of [peripheral]. The result is null when the
 * board has no SMP service, or when the service has no SMP characteristic. An engine that supplies
 * the characteristics only after `discoverCharacteristics` (`MacosJvmEngine`) also gives null
 * before that call.
 */
fun findSmpCharacteristic(peripheral: BluetoothPeripheral): BluetoothCharacteristic? =
    findSmpService(peripheral)?.characteristics?.firstOrNull { it.uuid == SmpUuids.Characteristic }
