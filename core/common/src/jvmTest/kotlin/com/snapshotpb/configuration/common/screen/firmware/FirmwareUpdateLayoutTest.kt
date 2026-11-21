package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/** Layout checks for [FirmwareUpdateContent] at every [DuoSize]. */
class FirmwareUpdateLayoutTest {

    private val updateStatus = FirmwareStatus(
        deviceId = DEVICE_ID,
        connected = true,
        boardVersion = FirmwareVersion(2026, 29, 1, dev = false),
        target = HardwareTarget(board = "snapshot_v2", qualifiers = "esp32c3"),
        latest = FirmwareManifest(
            version = LATEST_VERSION,
            tag = "v$LATEST_VERSION",
            channel = FirmwareChannel.STABLE,
            compatibleHardware = listOf("snapshot_v2"),
            assets = FirmwareAssets(
                image = FirmwareAsset(
                    name = "snapshot_v2-$LATEST_VERSION-app.signed.bin",
                    url = "https://snapshotpb.github.io/Firmware/snapshot_v2/$LATEST_VERSION/snapshot_v2-$LATEST_VERSION-app.signed.bin",
                    size = 900_000L,
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                ),
            ),
        ),
        reason = null,
    )

    @Test
    fun confirmPhaseFitsEveryDuoSize() {
        val state = FirmwareUpdateScreenState(
            status = updateStatus,
            phase = FirmwareUpdatePhase.Confirm(reason = null),
        )
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("firmware-confirm", size) {
                FirmwareUpdateContent(
                    state = state,
                    deviceName = DEVICE_NAME,
                    onStart = {},
                    onRetry = {},
                    onCancel = {},
                    onClose = {},
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
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
            val report = checkScreenLayout("firmware-upload", size) {
                FirmwareUpdateContent(
                    state = state,
                    deviceName = DEVICE_NAME,
                    onStart = {},
                    onRetry = {},
                    onCancel = {},
                    onClose = {},
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    private companion object {
        const val DEVICE_ID = "11111111-2222-3333-4444-555555555555"
        const val DEVICE_NAME = "Snapshot PB"
        const val LATEST_VERSION = "2026.43.1"
        const val UPLOAD_FRACTION = 0.42f
    }
}
