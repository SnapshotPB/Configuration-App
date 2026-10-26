package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HardwareTargetTest {

    @Test
    fun theBoardIsThePartBeforeTheFirstSlash() {
        val target = HardwareTarget.parse("snapshot_v2/esp32c3")
        assertEquals(HardwareTarget(board = "snapshot_v2", qualifiers = "esp32c3"), target)
        assertEquals("snapshot_v2", target?.board)
    }

    @Test
    fun keepsEveryQualifier() {
        val target = HardwareTarget.parse("esp32c3_devkitm/esp32c3/cpu0")
        assertEquals("esp32c3_devkitm", target?.board)
        assertEquals("esp32c3/cpu0", target?.qualifiers)
    }

    @Test
    fun aTargetWithNoQualifier_isTheBoardOnly() {
        assertEquals(HardwareTarget(board = "snapshot_v2"), HardwareTarget.parse("snapshot_v2"))
    }

    @Test
    fun aRevisionIsNotPartOfTheBoard() {
        val target = HardwareTarget.parse("snapshot_v2@1.0.0/esp32c3")
        assertEquals(HardwareTarget(board = "snapshot_v2", revision = "1.0.0", qualifiers = "esp32c3"), target)
    }

    @Test
    fun ignoresSpaceAtTheEnds() {
        assertEquals("snapshot_v2", HardwareTarget.parse("  snapshot_v2/esp32c3\n")?.board)
    }

    @Test
    fun refusesATargetWithNoBoard() {
        assertNull(HardwareTarget.parse(""))
        assertNull(HardwareTarget.parse("   "))
        assertNull(HardwareTarget.parse("/esp32c3"))
        assertNull(HardwareTarget.parse("@1.0/esp32c3"))
        assertNull(HardwareTarget.parse("snapshot v2/esp32c3"))
    }

    @Test
    fun theTextIsTheZephyrForm() {
        listOf("snapshot_v2/esp32c3", "snapshot_v2", "snapshot_v2@1.0.0/esp32c3").forEach {
            assertEquals(it, HardwareTarget.parse(it).toString())
        }
    }
}
