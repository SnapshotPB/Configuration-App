package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * Bolt actuation order for the firing cycle in cycled electronic fire modes
 * ([MarkerFireMode.usesBoltMode]). Stored at the device level (`DeviceConfig.bolt_mode` /
 * `BoltMode`: `BOLT_MODE_CLOSED` = [CLOSED], `BOLT_MODE_OPEN` = [OPEN]).
 */
@Serializable
enum class MarkerBoltMode {
    CLOSED,
    OPEN,
    ;

    val isOpenBolt: Boolean
        get() = this == OPEN

    companion object {
        val selectable: List<MarkerBoltMode> = entries
    }
}
