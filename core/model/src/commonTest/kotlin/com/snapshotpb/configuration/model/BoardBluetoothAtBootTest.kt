package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cover for the switch helpers on [BoardBluetoothAtBoot]. */
class BoardBluetoothAtBootTest {

    @Test
    fun remembersStateIsTrueOnlyForRestore() {
        assertFalse(BoardBluetoothAtBoot.OFF.remembersState)
        assertTrue(BoardBluetoothAtBoot.RESTORE.remembersState)
    }

    @Test
    fun ofMapsSwitchStateToSetting() {
        assertEquals(BoardBluetoothAtBoot.OFF, BoardBluetoothAtBoot.of(false))
        assertEquals(BoardBluetoothAtBoot.RESTORE, BoardBluetoothAtBoot.of(true))
    }
}
