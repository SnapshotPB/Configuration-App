package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Layout checks for [DeviceBrowserContent] at every [DuoSize]. */
class DeviceBrowserLayoutTest {

    private val knownDevices = listOf(
        layoutDevice(),
        layoutDevice(name = "Backup Marker").copy(id = BACKUP_DEVICE_ID),
    )

    private val scannedDevices = listOf(
        layoutDevice(name = "Snapshot PB").copy(id = SCANNED_DEVICE_1_ID),
        layoutDevice(name = "").copy(id = SCANNED_DEVICE_2_ID),
    )

    @Test
    fun devicesReadyFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("devices-ready", size) {
                DeviceBrowserContent(
                    knownDevices = knownDevices,
                    bluetoothState = BluetoothState.Ready,
                    scannedDevices = emptyList(),
                    pairsWithDeviceChooser = false,
                    systemSupportsBluetoothEnable = true,
                    actions = DeviceBrowserActions(),
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    @Test
    fun devicesScanningFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("devices-scanning", size) {
                DeviceBrowserContent(
                    knownDevices = knownDevices,
                    bluetoothState = BluetoothState.Scanning,
                    scannedDevices = scannedDevices,
                    pairsWithDeviceChooser = false,
                    systemSupportsBluetoothEnable = true,
                    actions = DeviceBrowserActions(),
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    private companion object {
        val BACKUP_DEVICE_ID = Uuid.parse("11111111-2222-3333-4444-555555555551")
        val SCANNED_DEVICE_1_ID = Uuid.parse("11111111-2222-3333-4444-555555555552")
        val SCANNED_DEVICE_2_ID = Uuid.parse("11111111-2222-3333-4444-555555555553")
    }
}
