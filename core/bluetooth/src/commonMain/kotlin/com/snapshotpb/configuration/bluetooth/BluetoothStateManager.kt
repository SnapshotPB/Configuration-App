package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * The 4-step progress of a connect attempt.
 *
 * [failureCode] is a short token that names the cause when a step FAILED: a fixed code, or the
 * simple class name of the exception. It never holds the text of an exception message. The
 * connect screen puts it into a test tag, and on iOS a test tag is the accessibility identifier,
 * also in a release build. Put the full detail in the log, not here. A class name is stable in
 * debug builds and on iOS, but R8 shortens class names in an Android release build.
 */
data class ConnectionProgress(
    val step1: StepStatus = StepStatus.PENDING, // Connection
    val step2: StepStatus = StepStatus.PENDING, // Service Discovery
    val step3: StepStatus = StepStatus.PENDING, // Device Info
    val step4: StepStatus = StepStatus.PENDING, // Profile Data
    val failureCode: String? = null,
    /**
     * The number of the link attempt in progress, from 1 to [maxAttempts]. It is 0 when no link
     * attempt is in progress. The engine sets it while it opens the link and discovers the
     * services (step 2).
     */
    val attempt: Int = 0,
    /** The maximum number of link attempts of the engine, or 0 when no link attempt is in progress. */
    val maxAttempts: Int = 0,
) {
    /** True while an attempt after the first one is in progress, and no step failed. */
    val isRetrying: Boolean
        get() = attempt > 1 && !isFailed && !isCompleted

    val isCompleted: Boolean
        get() = step1 == StepStatus.COMPLETED &&
                step2 == StepStatus.COMPLETED &&
                step3 == StepStatus.COMPLETED &&
                step4 == StepStatus.COMPLETED

    val isFailed: Boolean
        get() = step1 == StepStatus.FAILED ||
                step2 == StepStatus.FAILED ||
                step3 == StepStatus.FAILED ||
                step4 == StepStatus.FAILED
}

class BluetoothStateManager {
    private val _bluetoothState: MutableStateFlow<BluetoothState> =
        MutableStateFlow(BluetoothState.NeedsPermission)
    val bluetoothState: StateFlow<BluetoothState> get() = _bluetoothState

    private val _connectedDevices: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())
    val connectedDevices: StateFlow<Set<String>> get() = _connectedDevices

    private val _connectionProgress: MutableStateFlow<ConnectionProgress> =
        MutableStateFlow(ConnectionProgress())
    val connectionProgress: StateFlow<ConnectionProgress> get() = _connectionProgress

    private val _boardIds: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

    /**
     * The board `device_id` of each scan key that this session knows, as scan key to board id.
     *
     * A scan key is the transport key of a peripheral (a MAC hash on Android, the CBPeripheral UUID
     * on iOS). It is different on each phone and each platform, so it is not the identity of a board.
     * The board id is. The engine adds an entry after each read of the board, and the Android scan
     * adds one from the 0xFFE1 service data of the advertisement (#128). The entries are not
     * persisted, because a scan key is valid only for the session. See [resolveBoardId].
     */
    val boardIds: StateFlow<Map<String, String>> get() = _boardIds

    fun update(bluetoothState: BluetoothState) = _bluetoothState.update { bluetoothState }

    /**
     * Records that the peripheral with [scanKey] is the board with [boardId]. A board has one scan
     * key: the new key replaces an older key of the same board, because a board can change its
     * address during a session, and the older key then finds a peripheral that is gone.
     */
    fun recordBoardId(scanKey: String, boardId: String) {
        _boardIds.update { ids ->
            if (ids[scanKey] == boardId) ids else ids.filterValues { it != boardId } + (scanKey to boardId)
        }
    }

    fun updateProgress(progress: ConnectionProgress) {
        _connectionProgress.value = progress
    }

    fun resetProgress() {
        _connectionProgress.value = ConnectionProgress()
    }

    fun deviceConnected(deviceId: String) {
        _connectedDevices.update { it + deviceId }
    }

    /**
     * Marks [deviceId] as disconnected and removes its recorded eye state.
     *
     * The eye watcher count does not change, so a screen can keep its watch over a reconnect.
     */
    fun deviceDisconnected(deviceId: String) {
        _connectedDevices.update { it - deviceId }
        _eyeReadings.update { it - deviceId }
    }

    private val _updateServices: MutableStateFlow<Map<String, BoardUpdateService>> = MutableStateFlow(emptyMap())

    /**
     * The firmware update service of each connected board, by board id. The engine (or the
     * simulated board) adds an entry after the 4-step connect and removes it when the link closes.
     * A board with no entry is not connected, or its connect did not finish.
     */
    val updateServices: StateFlow<Map<String, BoardUpdateService>> get() = _updateServices

    fun recordUpdateService(boardId: String, service: BoardUpdateService) {
        _updateServices.update { services -> services + (boardId to service) }
    }

    fun clearUpdateService(boardId: String) {
        _updateServices.update { services -> services - boardId }
    }

    private val _firmwareUpload: MutableStateFlow<FirmwareUploadState> = MutableStateFlow(FirmwareUploadState())

    /**
     * The state of the last firmware request ([BluetoothRequest.UpdateFirmware] or
     * [BluetoothRequest.ConfirmFirmware]). One update runs at a time. Shared code reads the update
     * flow from `FirmwareUpdateCoordinator` in `core:firmware`, not from here.
     */
    val firmwareUpload: StateFlow<FirmwareUploadState> get() = _firmwareUpload

    fun updateFirmwareUpload(state: FirmwareUploadState) {
        _firmwareUpload.value = state
    }

    private val _eyeReadings: MutableStateFlow<Map<String, EyeReadingState>> = MutableStateFlow(emptyMap())
    private val _eyeWatchers: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    /**
     * The number of eye watchers of each board id.
     *
     * The key is the board id, the same key as [connectedDevices] and [updateServices]. A board
     * with no watcher has no entry.
     */
    val eyeWatchers: StateFlow<Map<String, Int>> get() = _eyeWatchers

    /**
     * The eye watcher count of [deviceId]: 0 when it has no watcher.
     *
     * [deviceId] is the board id, the same key as [connectedDevices] and [updateServices]. This
     * flow emits the current count first.
     */
    fun eyeWatcherCount(deviceId: String): Flow<Int> =
        eyeWatchers.map { it[deviceId] ?: 0 }.distinctUntilChanged()

    /**
     * The live eye state of [deviceId]. A collector of this flow is a watcher.
     *
     * [deviceId] is the board id, the same key as [connectedDevices] and [updateServices]. The
     * flow emits `null` while the board is not connected, and [EyeReadingState.Waiting] when the
     * board is connected with no recorded state.
     *
     * This API has no watch request on [BluetoothRequestManager]. That request bus has no buffer
     * and can lose a request, and a cancelled scope cannot send an unwatch request.
     */
    fun eyeReading(deviceId: String): Flow<EyeReadingState?> = flow {
        addEyeWatcher(deviceId)
        try {
            emitAll(
                combine(connectedDevices, _eyeReadings) { connected, readings ->
                    if (deviceId !in connected) null else readings[deviceId] ?: EyeReadingState.Waiting
                }.distinctUntilChanged(),
            )
        } finally {
            removeEyeWatcher(deviceId)
        }
    }

    /**
     * Records the eye state of [deviceId]. The engine and the simulated boards call it.
     *
     * [deviceId] is the board id, the same key as [connectedDevices] and [updateServices].
     */
    fun setEyeReading(deviceId: String, state: EyeReadingState) {
        _eyeReadings.update { readings -> readings + (deviceId to state) }
    }

    /**
     * Removes the eye state of [deviceId], for example at the teardown of its link.
     *
     * [deviceId] is the board id, the same key as [connectedDevices] and [updateServices].
     */
    fun clearEyeReading(deviceId: String) {
        _eyeReadings.update { readings -> readings - deviceId }
    }

    private fun addEyeWatcher(deviceId: String) {
        _eyeWatchers.update { watchers ->
            val count = (watchers[deviceId] ?: 0) + 1
            watchers + (deviceId to count)
        }
    }

    private fun removeEyeWatcher(deviceId: String) {
        _eyeWatchers.update { watchers ->
            val count = (watchers[deviceId] ?: 0) - 1
            if (count <= 0) watchers - deviceId else watchers + (deviceId to count)
        }
    }
}
