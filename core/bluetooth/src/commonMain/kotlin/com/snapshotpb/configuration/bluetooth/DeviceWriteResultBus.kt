package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Carries the outcome of an OTS device-configuration write out of BluetoothManager.
 *
 * A failed board write must never crash the app: the write is serviced on the
 * request-bus collector, so an uncaught throw there both kills the process and
 * tears down the bus. The platform managers catch the failure and report it here
 * instead, so shared UI can clear divergence (cached `boardState`) on success or
 * surface an error on failure. Mirrors [DeviceReadResultBus].
 */
class DeviceWriteResultBus {
    private val _results = MutableSharedFlow<DeviceWriteResult>(extraBufferCapacity = 8)
    val results: SharedFlow<DeviceWriteResult> = _results

    suspend fun emit(result: DeviceWriteResult) = _results.emit(result)
}

/**
 * The outcome of an OTS device-configuration write.
 *
 * [device] is the CLAMPED configuration actually written to the board — the engine bounds every
 * value to the firmware ranges before writing, so this is exactly what the board now holds. Its
 * language is the one the board reported after the write, because the board ignores a language
 * that it does not draw.
 * [requestedDevice] is the pre-clamp device as requested by the caller; the write-result observer
 * uses it to tell "the firmware clamped what I sent" (adopt [device] into the editable device)
 * apart from "the user edited a field while the write was in flight" (keep the newer edit). [error]
 * is null on success, otherwise the failure that prevented the write from completing.
 */
data class DeviceWriteResult(
    val device: Device,
    val error: Throwable?,
    val requestedDevice: Device? = null,
    /** The [BluetoothRequest.WriteDevice.requestId] of the write that this result reports. */
    val requestId: Long = 0L,
)

/**
 * The error of a write that did not start, because the board no longer holds what the app last
 * read from it. The usual cause is an edit in the board menu while the app is connected.
 *
 * The engine sends the new board state on [DeviceReadResultBus] before it reports this error, so
 * the sync rules show the board edit, and a Save does not overwrite it.
 */
class BoardChangedBeforeWriteException(deviceId: String) :
    IllegalStateException("Board $deviceId changed since the last read; the write did not start")
