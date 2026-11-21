package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutFirmwareStatus
import kotlin.test.Test

/** Layout checks for [FirmwareUpdateContent] at every [DuoSize]. */
class FirmwareUpdateLayoutTest {

    private val updateStatus = layoutFirmwareStatus()

    @Test
    fun confirmPhaseFitsEveryDuoSize() {
        val state = FirmwareUpdateScreenState(
            status = updateStatus,
            phase = FirmwareUpdatePhase.Confirm(reason = null),
        )
        for (size in DuoSize.entries) {
            checkScreenLayout("firmware-confirm", size) {
                FirmwareUpdateContent(
                    state = state,
                    deviceName = DEVICE_NAME,
                    onStart = {},
                    onRetry = {},
                    onCancel = {},
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun uploadStepFitsEveryDuoSize() {
        val state = FirmwareUpdateScreenState(
            status = updateStatus,
            phase = FirmwareUpdatePhase.Running(
                step = FirmwareStep.UPLOAD,
                fraction = UPLOAD_FRACTION,
                cancelEffect = cancelEffectAt(FirmwareStep.UPLOAD),
            ),
        )
        for (size in DuoSize.entries) {
            checkScreenLayout("firmware-upload", size) {
                FirmwareUpdateContent(
                    state = state,
                    deviceName = DEVICE_NAME,
                    onStart = {},
                    onRetry = {},
                    onCancel = {},
                    onClose = {},
                )
            }
        }
    }

    private companion object {
        const val DEVICE_NAME = "Snapshot"
        const val UPLOAD_FRACTION = 0.42f
    }
}
