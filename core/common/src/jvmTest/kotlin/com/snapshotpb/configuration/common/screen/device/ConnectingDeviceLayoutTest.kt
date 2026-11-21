package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Layout checks for [ConnectingProgress] at every [DuoSize]. */
class ConnectingDeviceLayoutTest {

    @Test
    fun connectingProgressFitsEveryDuoSize() {
        val progress = ConnectionProgress(
            step1 = StepStatus.COMPLETED,
            step2 = StepStatus.IN_PROGRESS,
            step3 = StepStatus.PENDING,
            step4 = StepStatus.PENDING,
        )
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("connecting-progress", size) {
                ConnectingProgress(
                    deviceName = DEVICE_NAME,
                    fwVersion = FW_VERSION,
                    bluetoothState = BluetoothState.Ready,
                    connectionProgress = progress,
                    isCompleted = false,
                    onFinish = {},
                    onBack = {},
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    @Test
    fun connectingCompleteFitsEveryDuoSize() {
        val progress = ConnectionProgress(
            step1 = StepStatus.COMPLETED,
            step2 = StepStatus.COMPLETED,
            step3 = StepStatus.COMPLETED,
            step4 = StepStatus.COMPLETED,
        )
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("connecting-complete", size) {
                ConnectingProgress(
                    deviceName = DEVICE_NAME,
                    fwVersion = FW_VERSION,
                    bluetoothState = BluetoothState.Ready,
                    connectionProgress = progress,
                    isCompleted = true,
                    onFinish = {},
                    onBack = {},
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    private companion object {
        const val DEVICE_NAME = "Snapshot PB"
        const val FW_VERSION = "1.4.0"
    }
}
