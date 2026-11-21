package com.snapshotpb.configuration.common.screen.log

import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.bluetooth_log_board_absent
import com.snapshotpb.core.resources.bluetooth_log_board_failed
import com.snapshotpb.core.resources.bluetooth_log_board_not_visible
import com.snapshotpb.core.resources.bluetooth_log_no_session
import com.snapshotpb.core.resources.bluetooth_log_requested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BluetoothLogScreenTest {

    @Test
    fun boardLogStateMessageMapsEachState() {
        assertEquals(Res.string.bluetooth_log_no_session, boardLogStateMessage(BoardLogState.NO_SESSION))
        assertEquals(Res.string.bluetooth_log_requested, boardLogStateMessage(BoardLogState.REQUESTED))
        assertNull(boardLogStateMessage(BoardLogState.RECEIVING))
        assertEquals(Res.string.bluetooth_log_board_absent, boardLogStateMessage(BoardLogState.ABSENT))
        assertEquals(Res.string.bluetooth_log_board_not_visible, boardLogStateMessage(BoardLogState.NOT_VISIBLE))
        assertEquals(Res.string.bluetooth_log_board_failed, boardLogStateMessage(BoardLogState.FAILED))
    }
}
