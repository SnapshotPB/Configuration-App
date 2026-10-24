package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A coordinator that records the calls of a view model. A test sets the flows. */
internal class FakeFirmwareUpdateCoordinator(initialStatus: FirmwareStatus) : FirmwareUpdateCoordinator {

    override val progress = MutableStateFlow<FirmwareUpdateProgress>(FirmwareUpdateProgress.Idle)
    override val deviceId = MutableStateFlow<String?>(null)

    /** The status of each board. A test changes it. */
    val statuses = MutableStateFlow(mapOf(initialStatus.deviceId to initialStatus))

    val calls = mutableListOf<String>()

    override fun status(deviceId: String, channel: FirmwareChannel): Flow<FirmwareStatus> =
        statuses.map { it.getValue(deviceId) }

    override fun refresh() {
        calls += "refresh"
    }

    override fun start(deviceId: String, channel: FirmwareChannel) {
        calls += "start:$deviceId"
    }

    override fun retry() {
        calls += "retry"
    }

    override fun cancel() {
        calls += "cancel"
    }

    override fun dismiss() {
        calls += "dismiss"
    }

    /** Sets the update of [board] to [step]. */
    fun report(board: String, step: FirmwareUpdateProgress) {
        deviceId.value = board
        progress.value = step
    }

    /** Changes the status of the board of [status]. */
    fun setStatus(status: FirmwareStatus) {
        statuses.value += status.deviceId to status
    }
}

internal const val BOARD = "11111111-2222-3333-4444-555555555555"
internal const val OTHER_BOARD = "99999999-2222-3333-4444-555555555555"

internal val boardVersion = FirmwareVersion(2026, 29, 1, dev = false)
internal val newVersion = FirmwareVersion(2026, 43, 1, dev = false)

internal fun release(version: String = newVersion.toString(), warning: String? = null) = FirmwareManifest(
    version = version,
    channel = FirmwareChannel.STABLE,
    compatibleHardware = listOf("snapshot_v2"),
    warning = warning,
    assets = FirmwareAssets(
        image = FirmwareAsset(name = "app.signed.bin", url = "http://localhost/app.signed.bin", size = 10, sha256 = "00"),
    ),
)

/** A status of [board]. With no [reason], the board can get the update. */
internal fun status(
    board: String = BOARD,
    reason: FirmwareUnavailableReason? = null,
    latest: FirmwareManifest? = release(),
) = FirmwareStatus(
    deviceId = board,
    connected = reason != FirmwareUnavailableReason.NOT_CONNECTED,
    boardVersion = boardVersion,
    target = HardwareTarget(board = "snapshot_v2", qualifiers = "esp32c3"),
    latest = latest,
    reason = reason,
)
