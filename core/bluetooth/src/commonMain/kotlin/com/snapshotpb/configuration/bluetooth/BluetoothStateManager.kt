package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun deviceDisconnected(deviceId: String) {
        _connectedDevices.update { it - deviceId }
    }
}
