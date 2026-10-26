package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareVersionTest {

    @Test
    fun parsesTheVersionOfTheSite() {
        assertEquals(FirmwareVersion(2026, 43, 1, dev = false), FirmwareVersion.parse("2026.43.1"))
        assertEquals(FirmwareVersion(2027, 1, 1, dev = false), FirmwareVersion.parse("2027.1.1"))
    }

    @Test
    fun aSuffixMarksADevBuild() {
        assertEquals(FirmwareVersion(2026, 29, 1, dev = true), FirmwareVersion.parse("2026.29.1-dev"))
        assertEquals(FirmwareVersion(2026, 29, 1, dev = true), FirmwareVersion.parse("2026.29.1-rc.2"))
    }

    @Test
    fun parseIgnoresSpaceAtTheEnds() {
        assertEquals(FirmwareVersion(2026, 43, 1, dev = false), FirmwareVersion.parse(" 2026.43.1\n"))
    }

    @Test
    fun parseRefusesOtherText() {
        listOf(
            "",
            "26.43.1",
            "v2026.43.1",
            "2026.43",
            "2026.43.1.2",
            "2026.143.1",
            "2026.43.1-",
            "2026.43.x",
        ).forEach { assertNull(FirmwareVersion.parse(it), "\"$it\" parsed") }
    }

    @Test
    fun parseRefusesNumbersThatTheAdvertisementCannotHold() {
        // The advertisement holds year - 2000 and the count in one byte each.
        assertNull(FirmwareVersion.parse("1999.43.1"))
        assertNull(FirmwareVersion.parse("2256.43.1"))
        assertNull(FirmwareVersion.parse("2026.43.256"))
        assertEquals(FirmwareVersion(2255, 43, 255, dev = false), FirmwareVersion.parse("2255.43.255"))
    }

    @Test
    fun theTextOfARelease_parsesToTheVersionOfTheSameAdvertisement() {
        // The advertisement of 2026.43.1: [26, 43, 1, flags 0].
        val advertised = FirmwareVersion.decode(byteArrayOf(26, 43, 1, 0))
        assertEquals(advertised, FirmwareVersion.parse("2026.43.1"))
        assertEquals(advertised.toString(), "2026.43.1")
    }

    @Test
    fun aGreaterReleaseIsNewer() {
        val board = FirmwareVersion(2026, 43, 1, dev = false)
        assertTrue(FirmwareVersion(2026, 43, 2, dev = false).isNewerThan(board))
        assertTrue(FirmwareVersion(2026, 44, 1, dev = false).isNewerThan(board))
        assertTrue(FirmwareVersion(2027, 1, 1, dev = false).isNewerThan(board))
    }

    @Test
    fun theSameOrALowerReleaseIsNotNewer() {
        val board = FirmwareVersion(2026, 43, 1, dev = false)
        assertFalse(FirmwareVersion(2026, 43, 1, dev = false).isNewerThan(board))
        assertFalse(FirmwareVersion(2026, 42, 9, dev = false).isNewerThan(board))
        assertFalse(FirmwareVersion(2025, 52, 9, dev = false).isNewerThan(board))
    }

    @Test
    fun aReleaseIsNewerThanADevBuildWithTheSameNumbers() {
        val devBoard = FirmwareVersion(2026, 43, 1, dev = true)
        assertTrue(FirmwareVersion(2026, 43, 1, dev = false).isNewerThan(devBoard))
        assertFalse(FirmwareVersion(2026, 42, 1, dev = false).isNewerThan(devBoard))
    }
}
