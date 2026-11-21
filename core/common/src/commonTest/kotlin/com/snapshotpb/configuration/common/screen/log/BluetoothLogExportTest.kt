package com.snapshotpb.configuration.common.screen.log

import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEntry
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSource
import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BluetoothLogExportTest {

    @Test
    fun formatBuildsExactHeaderAndAlignedEntries() {
        val boardTime = Instant.parse("2026-11-20T10:11:12.345Z")
        val appTime = Instant.parse("2026-11-20T10:11:12.400Z")
        val exportTime = Instant.parse("2026-11-20T10:12:00.000Z")
        val snapshot = BluetoothLogSnapshot(
            entries = listOf(
                BluetoothLogEntry(
                    sequence = 1L,
                    timeMillis = boardTime.toEpochMilliseconds(),
                    session = 1,
                    source = BluetoothLogSource.BOARD,
                    text = "[00:00:01.234,567] bt: Bluetooth Logger Backend enabled.",
                ),
                BluetoothLogEntry(
                    sequence = 2L,
                    timeMillis = appTime.toEpochMilliseconds(),
                    session = 2,
                    source = BluetoothLogSource.APP,
                    text = "connect.attempt 1/3",
                ),
            ),
            boardLogState = BoardLogState.RECEIVING,
            droppedChunks = 3L,
        )

        val actual = BluetoothLogExport.format(
            snapshot = snapshot,
            appVersion = "1.2.3",
            now = exportTime,
        )

        val expected = """
            SnapshotPB Bluetooth log
            App version: 1.2.3
            Exported: 2026-11-20T10:12:00.000Z
            Board log state: RECEIVING
            Entries: 2
            Chunks not recorded: 3
            Columns: UTC time, session, source, text.

            2026-11-20T10:11:12.345Z s1 board [00:00:01.234,567] bt: Bluetooth Logger Backend enabled.
            2026-11-20T10:11:12.400Z s2 app   connect.attempt 1/3

        """.trimIndent()

        assertEquals(expected, actual)
    }

    @Test
    fun formatEmptySnapshotShowsNoEntries() {
        val actual = BluetoothLogExport.format(
            snapshot = BluetoothLogSnapshot(),
            appVersion = "1.2.3",
            now = Instant.parse("2026-11-20T10:12:00.000Z"),
        )

        assertTrue(actual.endsWith("\nNo entries.\n"))
        assertTrue("Entries: 0\n" in actual)
        assertTrue("Board log state: NO_SESSION\n" in actual)
    }

    @Test
    fun formatPadsZeroFractionMillisToThreeDigits() {
        val snapshot = BluetoothLogSnapshot(
            entries = listOf(
                BluetoothLogEntry(
                    sequence = 1L,
                    timeMillis = 5L,
                    session = 1,
                    source = BluetoothLogSource.APP,
                    text = "connect.request",
                ),
            ),
        )

        val actual = BluetoothLogExport.format(
            snapshot = snapshot,
            appVersion = "1.2.3",
            now = Instant.parse("2026-11-20T10:12:00.000Z"),
        )

        assertTrue("1970-01-01T00:00:00.005Z s1 app   connect.request\n" in actual)
    }

    @Test
    fun formatKeepsTabInEntryText() {
        val snapshot = BluetoothLogSnapshot(
            entries = listOf(
                BluetoothLogEntry(
                    sequence = 1L,
                    timeMillis = Instant.parse("2026-11-20T10:11:12.345Z").toEpochMilliseconds(),
                    session = 1,
                    source = BluetoothLogSource.BOARD,
                    text = "col1\tcol2",
                ),
            ),
        )

        val actual = BluetoothLogExport.format(
            snapshot = snapshot,
            appVersion = "1.2.3",
            now = Instant.parse("2026-11-20T10:12:00.000Z"),
        )

        assertTrue("2026-11-20T10:11:12.345Z s1 board col1\tcol2\n" in actual)
    }

    @Test
    fun fileNameUsesUtcTimestamp() {
        val now = Instant.parse("2026-01-02T03:04:05.678Z")
        assertEquals("snapshotpb-bluetooth-log-20260102-030405.txt", BluetoothLogExport.fileName(now))
    }

    @Test
    fun clockTimeFormatsHoursMinutesSecondsAndMillis() {
        val instant = Instant.parse("2026-11-20T10:11:12.345Z")
        assertEquals("10:11:12.345", bluetoothLogClockTime(instant.toEpochMilliseconds(), TimeZone.UTC))
        assertEquals("00:00:00.005", bluetoothLogClockTime(5L, TimeZone.UTC))
        val minusFive = FixedOffsetTimeZone(UtcOffset(hours = -5))
        assertEquals("05:11:12.345", bluetoothLogClockTime(instant.toEpochMilliseconds(), minusFive))
    }
}
