package com.snapshotpb.configuration.common.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.new_device_screen_cancel_button
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
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
            checkScreenLayout("connecting-progress", size) {
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
        }
    }

    /**
     * [ConnectingProgress] has no vertical scroll. In a one-column window that is 415 dp high, the
     * column is too tall and cuts its last rows. The plan records this as a known defect. When a
     * change to the screen corrects the defect, change this test to assert that no text is clipped.
     */
    @Test
    fun progress_clipsCancelInShortWindow_knownDefect() {
        val cancelLabel = runBlocking { getString(Res.string.new_device_screen_cancel_button) }

        val landscapeReport = checkScreenLayout("connecting-progress-defect", DuoSize.OUTER_LANDSCAPE) {
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
            cancelLabel in landscapeReport.clippedTexts,
            "Expected \"$cancelLabel\" in clippedTexts at ${DuoSize.OUTER_LANDSCAPE.label}, got ${landscapeReport.clippedTexts}",
        )

        val portraitReport = checkScreenLayout("connecting-progress-defect", DuoSize.INNER_ROTATED) {
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
            cancelLabel in portraitReport.clippedTexts,
            "Expected \"$cancelLabel\" not in clippedTexts at ${DuoSize.INNER_ROTATED.label}, got ${portraitReport.clippedTexts}",
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
            checkScreenLayout("connecting-complete", size) {
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
        }
    }

    private companion object {
        const val DEVICE_NAME = "Snapshot PB"
        const val FW_VERSION = "1.4.0"
    }
}
