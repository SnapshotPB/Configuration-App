package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Layout checks for [ConnectingProgress] at every [DuoSize]. */
class ConnectingDeviceLayoutTest {

    private val inProgress = ConnectionProgress(
        step1 = StepStatus.COMPLETED,
        step2 = StepStatus.IN_PROGRESS,
        step3 = StepStatus.PENDING,
        step4 = StepStatus.PENDING,
    )

    @Test
    fun connectingProgressFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("connecting-progress", size) {
                ConnectingProgress(
                    deviceName = DEVICE_NAME,
                    fwVersion = FW_VERSION,
                    bluetoothState = BluetoothState.Ready,
                    connectionProgress = inProgress,
                    isCompleted = false,
                    onFinish = {},
                    onBack = {},
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    /**
     * [ConnectingProgress] has no vertical scroll, so a window that is 466 dp high cuts its last
     * rows. The plan records this as a known defect. When a change to the screen corrects the
     * defect, change this test to assert that no text is clipped.
     */
    @Test
    fun progress_clipsCancelInShortWindow_knownDefect() {
        val landscapeReport = checkScreenLayout("connecting-progress", DuoSize.OUTER_LANDSCAPE_FULL) {
            ConnectingProgress(
                deviceName = DEVICE_NAME,
                fwVersion = FW_VERSION,
                bluetoothState = BluetoothState.Ready,
                connectionProgress = inProgress,
                isCompleted = false,
                onFinish = {},
                onBack = {},
            )
        }
        assertTrue(
            CANCEL_LABEL in landscapeReport.clippedTexts,
            "Expected \"$CANCEL_LABEL\" in clippedTexts at ${DuoSize.OUTER_LANDSCAPE_FULL.label}, got ${landscapeReport.clippedTexts}",
        )

        val portraitReport = checkScreenLayout("connecting-progress", DuoSize.INNER_ROTATED) {
            ConnectingProgress(
                deviceName = DEVICE_NAME,
                fwVersion = FW_VERSION,
                bluetoothState = BluetoothState.Ready,
                connectionProgress = inProgress,
                isCompleted = false,
                onFinish = {},
                onBack = {},
            )
        }
        assertFalse(
            CANCEL_LABEL in portraitReport.clippedTexts,
            "Expected \"$CANCEL_LABEL\" not in clippedTexts at ${DuoSize.INNER_ROTATED.label}, got ${portraitReport.clippedTexts}",
        )
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
        const val CANCEL_LABEL = "Cancel"
    }
}
