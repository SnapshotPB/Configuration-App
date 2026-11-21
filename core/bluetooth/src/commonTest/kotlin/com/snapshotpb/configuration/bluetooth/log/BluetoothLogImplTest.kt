package com.snapshotpb.configuration.bluetooth.log

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothLogImplTest {

    private class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
    }

    @Test
    fun startSession_addsStartedEntryAtOnceAndKeepsNoSessionState() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))

        log.startSession(1)
        runCurrent()

        val snapshot = log.snapshot.value
        assertEquals(BoardLogState.NO_SESSION, snapshot.boardLogState)
        assertEquals(1, snapshot.entries.size)
        val entry = snapshot.entries.single()
        assertEquals(1, entry.session)
        assertEquals(BluetoothLogSource.APP, entry.source)
        assertEquals("session 1 started", entry.text)
    }

    @Test
    fun boardChunk_twoLinesInOneChunk_addsTwoBoardEntriesAndSetsReceiving() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        log.boardChunk(1, "line one\r\nline two\r\n".encodeToByteArray())
        runCurrent()

        val snapshot = log.snapshot.value
        assertEquals(BoardLogState.RECEIVING, snapshot.boardLogState)
        val boardEntries = snapshot.entries.filter { it.source == BluetoothLogSource.BOARD }
        assertEquals(listOf("line one", "line two"), boardEntries.map { it.text })
        assertTrue(boardEntries.all { it.session == 1 })
    }

    @Test
    fun boardLogState_requestedAfterFirstChunk_keepsReceiving() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        log.boardChunk(1, "first line\r\n".encodeToByteArray())
        log.boardLogState(1, BoardLogState.REQUESTED)
        runCurrent()

        assertEquals(BoardLogState.RECEIVING, log.snapshot.value.boardLogState)
    }

    @Test
    fun boardLogState_absent_setsAbsentAtOnce() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        log.boardLogState(1, BoardLogState.ABSENT)
        runCurrent()

        assertEquals(BoardLogState.ABSENT, log.snapshot.value.boardLogState)
    }

    @Test
    fun boardLogState_changesAddEntriesAndIgnoreRepeatsAndFirstChunk() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        log.boardLogState(1, BoardLogState.ABSENT)
        log.boardLogState(1, BoardLogState.ABSENT)
        log.boardLogState(1, BoardLogState.NOT_VISIBLE)
        runCurrent()

        advanceTimeBy(250)
        log.boardChunk(1, "board line\r\n".encodeToByteArray())
        log.boardLogState(1, BoardLogState.REQUESTED)
        runCurrent()

        assertEquals(BoardLogState.RECEIVING, log.snapshot.value.boardLogState)
        assertEquals(
            listOf(
                BluetoothLogSource.APP to "session 1 started",
                BluetoothLogSource.APP to "session 1: board log absent",
                BluetoothLogSource.APP to "session 1: board log not_visible",
                BluetoothLogSource.BOARD to "board line",
            ),
            log.snapshot.value.entries.map { it.source to it.text },
        )
    }

    @Test
    fun sequenceNumbers_goUpByOneAndContinueAfterClear() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        log.app(1, BluetoothLogEvent.CONNECT_REQUESTED)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf(1L, 2L), log.snapshot.value.entries.map { it.sequence })

        log.clear()
        log.startSession(2)
        runCurrent()

        assertEquals(listOf(3L), log.snapshot.value.entries.map { it.sequence })
    }

    @Test
    fun entryTime_usesClockTimeOfCall() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        advanceTimeBy(5_000)

        log.startSession(1)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(5_000L, log.snapshot.value.entries.single().timeMillis)
    }

    @Test
    fun sessionBoard_labelsBoardsDeduplicatesAndHidesBoardId() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        val firstBoardId = "AC:EB:E6:81:A8:5C"
        val secondBoardId = "11:22:33:44:55:66"
        log.startSession(1)

        log.sessionBoard(1, firstBoardId, "26.47.0")
        log.sessionBoard(1, firstBoardId, "26.47.0")
        log.sessionBoard(1, firstBoardId, null)
        log.sessionBoard(1, secondBoardId, "26.48.0")
        log.sessionBoard(1, firstBoardId, "26.48.0")
        runCurrent()

        val texts = log.snapshot.value.entries.drop(1).map { it.text }
        assertEquals(
            listOf(
                "session 1: board 1, firmware 26.47.0",
                "session 1: board 1, firmware unknown",
                "session 1: board 2, firmware 26.48.0",
                "session 1: board 1, firmware 26.48.0",
            ),
            texts,
        )
        assertFalse(log.snapshot.value.entries.any { firstBoardId in it.text || secondBoardId in it.text })
    }

    @Test
    fun endSession_flushesOpenPartLineAddsEndedResetsStateAndIgnoresSecondCall() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        log.boardChunk(1, "cut line".encodeToByteArray())
        runCurrent()

        log.endSession(1)
        runCurrent()

        val afterFirstEnd = log.snapshot.value
        assertEquals(BoardLogState.NO_SESSION, afterFirstEnd.boardLogState)
        assertEquals(
            listOf(
                BluetoothLogSource.APP to "session 1 started",
                BluetoothLogSource.BOARD to "cut line${BoardLogLineAssembler.CUT_SUFFIX}",
                BluetoothLogSource.APP to "session 1 ended",
            ),
            afterFirstEnd.entries.map { it.source to it.text },
        )

        log.endSession(1)
        runCurrent()

        assertEquals(afterFirstEnd.entries, log.snapshot.value.entries)
    }

    @Test
    fun boardChunk_forUnopenedOrEndedSession_addsNoEntry() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.boardChunk(99, "ignored\r\n".encodeToByteArray())
        log.startSession(1)
        log.endSession(1)
        log.boardChunk(1, "after end\r\n".encodeToByteArray())
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(
            listOf("session 1 started", "session 1 ended"),
            log.snapshot.value.entries.map { it.text },
        )
    }

    @Test
    fun app_recordsCodeAndOptionalDetail() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.app(0, BluetoothLogEvent.CONNECT_REQUESTED)
        log.app(1, BluetoothLogEvent.CONNECT_ATTEMPT, "2/3")
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val entries = log.snapshot.value.entries
        assertEquals(2, entries.size)
        assertEquals(0, entries[0].session)
        assertEquals(BluetoothLogSource.APP, entries[0].source)
        assertEquals("connect.request", entries[0].text)
        assertEquals(1, entries[1].session)
        assertEquals(BluetoothLogSource.APP, entries[1].source)
        assertEquals("connect.attempt 2/3", entries[1].text)
    }

    @Test
    fun boardChunk_masksAddressAndUuidInEntry() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        log.boardChunk(
            1,
            "peer AA:BB:CC:DD:EE:FF svc 6E400001-B5A3-F393-E0A9-E50E24DCCA9E\r\n".encodeToByteArray(),
        )
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val boardEntry = log.snapshot.value.entries.last()
        assertEquals("peer XX:XX:XX:XX:XX:XX svc <uuid>", boardEntry.text)
    }

    @Test
    fun boardChunk_lineOf600Characters_cutsEntryTo512Characters() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        val line = "a".repeat(600)
        log.startSession(1)
        log.boardChunk(1, "$line\r\n".encodeToByteArray())
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val boardEntry = log.snapshot.value.entries.last()
        assertEquals(512, boardEntry.text.length)
        assertEquals("a".repeat(512), boardEntry.text)
    }

    @Test
    fun retention_aboveEntryLimit_dropsOldestEntries() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        for (index in 1..2_100) {
            log.boardChunk(1, "line $index\r\n".encodeToByteArray())
            if (index % 100 == 0) {
                runCurrent()
            }
        }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val entries = log.snapshot.value.entries
        assertEquals(2_000, entries.size)
        assertEquals("line 101", entries.first().text)
        assertEquals("line 2100", entries.last().text)
    }

    @Test
    fun retention_aboveTextCharLimit_dropsOldestAndKeepsNewest() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        var lastLine = ""
        for (index in 1..600) {
            val prefix = "n=$index:"
            val line = prefix + "x".repeat(500 - prefix.length)
            lastLine = line
            log.boardChunk(1, "$line\r\n".encodeToByteArray())
            if (index % 100 == 0) {
                runCurrent()
            }
        }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val entries = log.snapshot.value.entries
        val totalChars = entries.sumOf { it.text.length }
        assertTrue(totalChars <= 262_144)
        assertEquals(lastLine, entries.last().text)
    }

    @Test
    fun publishInterval_throttlesBurstAndPublishesTailAtIntervalEnd() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()

        log.boardChunk(1, "line 1\r\n".encodeToByteArray())
        runCurrent()
        assertEquals(1, log.snapshot.value.entries.count { it.source == BluetoothLogSource.BOARD })

        for (index in 2..100) {
            advanceTimeBy(1)
            log.boardChunk(1, "line $index\r\n".encodeToByteArray())
            runCurrent()
        }
        // Current virtual time is 1_099; advance 1 ms to reach 1_100.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, log.snapshot.value.entries.count { it.source == BluetoothLogSource.BOARD })

        // Advance 150 ms to reach 1_250.
        advanceTimeBy(150)
        runCurrent()
        assertEquals(100, log.snapshot.value.entries.count { it.source == BluetoothLogSource.BOARD })
    }

    @Test
    fun endSession_insidePublishInterval_publishesAtOnce() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        advanceTimeBy(10)
        log.boardChunk(1, "burst line\r\n".encodeToByteArray())
        runCurrent()
        assertEquals(0, log.snapshot.value.entries.count { it.source == BluetoothLogSource.BOARD })

        advanceTimeBy(10)
        log.endSession(1)
        runCurrent()

        assertEquals(
            listOf("session 1 started", "burst line", "session 1 ended"),
            log.snapshot.value.entries.map { it.text },
        )
    }

    @Test
    fun clear_publishesEmptyListAtOnceAndResetsDroppedChunks() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        val chunk = ("z".repeat(998) + "\r\n").encodeToByteArray()
        repeat(200) {
            log.boardChunk(1, chunk)
        }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertTrue(log.snapshot.value.entries.isNotEmpty())
        assertTrue(log.snapshot.value.droppedChunks > 0L)

        log.clear()
        runCurrent()

        val snapshot = log.snapshot.value
        assertEquals(emptyList(), snapshot.entries)
        assertEquals(0L, snapshot.droppedChunks)
    }

    @Test
    fun boardChunk_abovePendingByteLimit_dropsExcessAndAllowsEndSession() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        val chunk = ("y".repeat(998) + "\r\n").encodeToByteArray()
        repeat(200) {
            log.boardChunk(1, chunk)
        }

        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertTrue(log.snapshot.value.droppedChunks > 0L)

        log.endSession(1)
        runCurrent()

        assertEquals("session 1 ended", log.snapshot.value.entries.last().text)
    }

    @Test
    fun endSession_atExactEndOfPublishInterval_keepsEndedEntry() = runTest {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        log.startSession(1)
        runCurrent()

        advanceTimeBy(10)
        log.boardChunk(1, "burst line\r\n".encodeToByteArray())
        runCurrent()
        assertEquals(0, log.snapshot.value.entries.count { it.source == BluetoothLogSource.BOARD })

        // Advance to t = 250, the exact end of the publish interval, before runCurrent().
        advanceTimeBy(240)
        log.endSession(1)
        runCurrent()

        assertEquals(
            listOf("session 1 started", "burst line", "session 1 ended"),
            log.snapshot.value.entries.map { it.text },
        )
    }
}
