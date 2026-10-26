package com.snapshotpb.configuration.bluetooth.smp

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid

/**
 * The GATT UUIDs of the Zephyr MCUmgr SMP service (`subsys/mgmt/mcumgr/transport/src/smp_bt.c`).
 * The board does not advertise this service, so the scan filter of the app stays on the board
 * service. The web engine must have the service in its GATT allowlist. [findSmpCharacteristic]
 * finds the characteristic in the discovered services.
 */
object SmpUuids {
    /** The SMP service. */
    val Service: Uuid = "8D53DC1D-1DB7-4CD3-868B-8A527460AA84".toUuid()

    /**
     * The one characteristic of the service. It has the properties "write without response" and
     * "notify". A request goes out as writes, and a response comes back as notifications.
     */
    val Characteristic: Uuid = "DA2E7828-FBCE-4E01-AE9E-261174997C48".toUuid()
}
