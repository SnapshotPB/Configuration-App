package com.snapshotpb.configuration.bluetooth.log

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Nordic UART Service of the board log (Firmware ble/gatt.rs:157-162). */
object BoardLogUuids {
    val Service: Uuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E".toUuid()
    val Tx: Uuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E".toUuid()
}

enum class BluetoothLogSource { BOARD, APP }

/** One line of the log. [text] is clean and redacted, with a maximum of 512 characters. */
data class BluetoothLogEntry(
    val sequence: Long,
    val timeMillis: Long,
    val session: Int,
    val source: BluetoothLogSource,
    val text: String,
)

/** The state of the NUS subscription of the newest session. */
enum class BoardLogState { NO_SESSION, REQUESTED, RECEIVING, ABSENT, NOT_VISIBLE, FAILED }

data class BluetoothLogSnapshot(
    val entries: List<BluetoothLogEntry> = emptyList(),
    val boardLogState: BoardLogState = BoardLogState.NO_SESSION,
    val droppedChunks: Long = 0,
)

/** Phase 2 app events. [code] is the fixed English text of the entry. */
enum class BluetoothLogEvent(val code: String) {
    BLUETOOTH_NOT_READY("connect.not_ready"),
    CONNECT_REQUESTED("connect.request"),
    LIVE_REREAD("connect.reread"),
    CONNECT_ATTEMPT("connect.attempt"),
    SERVICES_FOUND("connect.services"),
    SERVICES_NOT_FOUND("connect.no_services"),
    OTS_READY("connect.ots_ready"),
    CONNECT_FAILED("connect.failed"),
    CONNECT_CANCELLED("connect.cancelled"),
    WRITE_STARTED("write.start"),
    WRITE_DONE("write.done"),
    WRITE_FAILED("write.failed"),
    BOARD_CHANGED_BEFORE_WRITE("write.board_changed"),
    UPDATE_SERVICE("update_service"),
    EYE_UNAVAILABLE("eye.unavailable"),
    EYE_SUBSCRIBED("eye.subscribe"),
    EYE_SUBSCRIBE_FAILED("eye.subscribe_failed"),
    EYE_UNSUBSCRIBED("eye.unsubscribe"),
    EYE_UNSUBSCRIBE_FAILED("eye.unsubscribe_failed"),
    FIRMWARE_STEP("firmware.step"),
    FIRMWARE_FAILED("firmware.failed"),
    DISCONNECT_REQUESTED("disconnect.request"),
    LINK_LOST("link.lost"),
}

/**
 * The Bluetooth log. No call suspends. The engine owns the session numbers (1, 2, 3, …) and calls
 * the session functions while it holds its connection lock.
 */
interface BluetoothLog {
    val snapshot: StateFlow<BluetoothLogSnapshot>

    /** False when the app must not subscribe to the board log (Wear). */
    val recordsBoardLines: Boolean

    fun startSession(session: Int)
    fun sessionBoard(session: Int, boardId: String, firmwareVersion: String?)
    fun boardChunk(session: Int, bytes: ByteArray)
    fun boardLogState(session: Int, state: BoardLogState)

    /** [detail] is a fixed code or, as a last choice, an exception class name. Never a message, an id or a name. */
    fun app(session: Int, event: BluetoothLogEvent, detail: String? = null)

    /** Ends [session]. A second call for the same session does nothing. */
    fun endSession(session: Int)
    fun clear()
}

/** A log that records nothing and subscribes to nothing. Wear binds it. T0 binds it as the default. */
object DisabledBluetoothLog : BluetoothLog {
    override val snapshot: StateFlow<BluetoothLogSnapshot> = MutableStateFlow(BluetoothLogSnapshot()).asStateFlow()
    override val recordsBoardLines: Boolean = false
    override fun startSession(session: Int) = Unit
    override fun sessionBoard(session: Int, boardId: String, firmwareVersion: String?) = Unit
    override fun boardChunk(session: Int, bytes: ByteArray) = Unit
    override fun boardLogState(session: Int, state: BoardLogState) = Unit
    override fun app(session: Int, event: BluetoothLogEvent, detail: String?) = Unit
    override fun endSession(session: Int) = Unit
    override fun clear() = Unit
}
