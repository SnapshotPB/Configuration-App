package com.snapshotpb.configuration.common.screen.device.composable

import androidx.compose.foundation.lazy.LazyListScope
import com.snapshotpb.configuration.model.Device

fun LazyListScope.KnownDeviceList(
    knownDevices: List<Device>,
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
    additionalItems: LazyListScope.() -> Unit = {}
) {
    knownDevices.forEachIndexed { index, device ->
        item(key = device.id.toString()) {
            KnownDeviceCard(
                first = index == 0,
                device = device,
                onDeviceSelected = onDeviceSelected,
                onDeviceDeleted = { onDeviceDeleted(device) }
            )
        }
    }

    additionalItems()
}
