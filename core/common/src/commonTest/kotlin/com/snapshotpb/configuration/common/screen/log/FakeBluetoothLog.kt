package com.snapshotpb.configuration.common.screen.log

import com.snapshotpb.configuration.bluetooth.log.BluetoothLog
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEvent
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake [BluetoothLog] for view model tests. */
class FakeBluetoothLog(
    initial: BluetoothLogSnapshot = BluetoothLogSnapshot(),
) : BluetoothLog {
    val flow: MutableStateFlow<BluetoothLogSnapshot> = MutableStateFlow(initial)
    override val snapshot: StateFlow<BluetoothLogSnapshot> = flow
    override val recordsBoardLines: Boolean = true
    var clearCalls: Int = 0

    override fun startSession(session: Int) = Unit
    override fun sessionBoard(session: Int, boardId: String, firmwareVersion: String?) = Unit
    override fun boardChunk(session: Int, bytes: ByteArray) = Unit
    override fun boardLogState(session: Int, state: BoardLogState) = Unit
    override fun app(session: Int, event: BluetoothLogEvent, detail: String?) = Unit
    override fun endSession(session: Int) = Unit
    override fun clear() {
        clearCalls++
    }
}
