package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutDevice
import com.snapshotpb.configuration.common.layout.noOpDeviceBrowserActions
import kotlin.test.Test
import kotlin.uuid.Uuid

/** Layout checks for [DeviceBrowserContent] at every [DuoSize]. */
class DeviceBrowserLayoutTest {

    private val knownDevices = listOf(
        layoutDevice(),
        layoutDevice(name = "Backup").copy(id = BACKUP_DEVICE_ID),
    )

    private val scannedDevices = listOf(
        layoutDevice(name = "Snapshot").copy(id = SCANNED_DEVICE_1_ID),
        layoutDevice(name = "").copy(id = SCANNED_DEVICE_2_ID),
    )

    @Test
    fun devicesReadyFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            checkScreenLayout("devices-ready", size) {
                DeviceBrowserContent(
                    knownDevices = knownDevices,
                    bluetoothState = BluetoothState.Ready,
                    scannedDevices = emptyList(),
                    pairsWithDeviceChooser = false,
                    systemSupportsBluetoothEnable = true,
                    actions = noOpDeviceBrowserActions(),
                )
            }
        }
    }

    @Test
    fun devicesScanningFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            checkScreenLayout("devices-scanning", size) {
                DeviceBrowserContent(
                    knownDevices = knownDevices,
                    bluetoothState = BluetoothState.Scanning,
                    scannedDevices = scannedDevices,
                    pairsWithDeviceChooser = false,
                    systemSupportsBluetoothEnable = true,
                    actions = noOpDeviceBrowserActions(),
                )
            }
        }
    }

    private companion object {
        val BACKUP_DEVICE_ID = Uuid.parse("11111111-2222-3333-4444-555555555551")
        val SCANNED_DEVICE_1_ID = Uuid.parse("11111111-2222-3333-4444-555555555552")
        val SCANNED_DEVICE_2_ID = Uuid.parse("11111111-2222-3333-4444-555555555553")
    }
}
