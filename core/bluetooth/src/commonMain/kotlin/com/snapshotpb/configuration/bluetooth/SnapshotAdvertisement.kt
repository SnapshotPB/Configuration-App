package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.ServiceFilter
import kotlin.uuid.Uuid

/**
 * The parts of the board advertisement that the app uses (#128). The firmware builds the
 * advertisement in `app/src/subsystems/bluetooth/bluetooth.c`, with the UUIDs from `app/Kconfig`:
 *
 * - Primary advertisement: the 16-bit service-class UUID [FilterService] (0xFFE2) in the complete
 *   list of 16-bit UUIDs, and the 16-byte board `device_id` as service data of [IdentityService]
 *   (0xFFE1).
 * - Scan response: the device name, and the board type and the firmware version as service data of
 *   [BoardInfoService] (0xFFE0). [com.snapshotpb.configuration.model.AdvertisementData] parses it.
 *
 * Discovery uses [FilterService], not the name, because the name is not reliable on iOS.
 */
object SnapshotAdvertisement {

    /** Service-class UUID that every scan filters on. Firmware `CONFIG_BT_SNAPSHOT_FILTER_SERVICE_UUID`. */
    val FilterService: Uuid = bluetoothBaseUuid(0xFFE2)

    /** Service data with the board type and the firmware version. Firmware `CONFIG_BT_BOARD_INFO_SERVICE_UUID`. */
    val BoardInfoService: Uuid = bluetoothBaseUuid(0xFFE0)

    /** Service data with the board `device_id`. Firmware `CONFIG_BT_BOARD_IDENTITY_SERVICE_UUID`. */
    val IdentityService: Uuid = bluetoothBaseUuid(0xFFE1)

    /** The filter of every scan: boards only. */
    val scanFilters: List<ServiceFilter> = listOf(ServiceFilter(FilterService))

    /**
     * True when [peripheral] is a Snapshot board.
     *
     * Each scan filters on [FilterService], so a peripheral that the scan found is a board. Android
     * copies the advertised service UUIDs to the peripheral, and the app checks them there too. The
     * BlueFalcon 3.7.14 engines of iOS, desktop and the web report no UUIDs, so the list is empty and
     * the scan filter of the engine is the only check. The web chooser has no filter, so there the
     * user picks the board.
     */
    fun isSnapshotBoard(peripheral: BluetoothPeripheral): Boolean {
        val advertised = peripheral.advertisedServiceUUIDs
        return advertised.isEmpty() || FilterService in advertised
    }

    /**
     * The board id in the service data of [IdentityService], or `null` when the data is not a
     * `device_id`. [serviceData] is the data after the 16-bit UUID. The firmware sends 16 zero bytes
     * when it cannot read its device state, and that is not an id.
     */
    fun boardIdOf(serviceData: ByteArray): String? {
        if (serviceData.size != DEVICE_ID_SIZE) return null
        if (serviceData.all { it == 0.toByte() }) return null
        return Uuid.fromByteArray(serviceData).toString()
    }

    private const val DEVICE_ID_SIZE = 16

    /** The 128-bit form of the 16-bit Bluetooth UUID [shortUuid] (Bluetooth base UUID). */
    private fun bluetoothBaseUuid(shortUuid: Int): Uuid =
        Uuid.parse("0000${shortUuid.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
}
