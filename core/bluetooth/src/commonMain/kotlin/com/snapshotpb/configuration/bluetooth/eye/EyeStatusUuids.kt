package com.snapshotpb.configuration.bluetooth.eye

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid

/** The eye status service of the board and its read and notify characteristic (Configuration-Protocol README, "Live eye status (GATT)"). */
object EyeStatusUuids {
    val Service: Uuid = "00000010-5350-4221-0000-5350425f4f54".toUuid()
    val Status: Uuid = "00000011-5350-4221-0000-5350425f4f54".toUuid()
}
