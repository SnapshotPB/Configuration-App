package com.snapshotpb.configuration.bluetooth.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardLogLineAssemblerTest {

    @Test
    fun append_oneLineWithCrLfInOneChunk_givesOneLine() {
        val assembler = BoardLogLineAssembler()

        val lines = assembler.append("[00:00:01.000,000] app: ready\r\n".encodeToByteArray())

        assertEquals(listOf("[00:00:01.000,000] app: ready"), lines)
    }

    @Test
    fun append_twoLinesInOneChunk_givesTwoLines() {
        val assembler = BoardLogLineAssembler()

        val lines = assembler.append(
            "[00:00:01.000,000] app: first\r\n[00:00:01.001,000] app: second\r\n".encodeToByteArray(),
        )

        assertEquals(
            listOf(
                "[00:00:01.000,000] app: first",
                "[00:00:01.001,000] app: second",
            ),
            lines,
        )
    }

    @Test
    fun append_lineInTwoChunks_givesOneLineAtSecondChunk() {
        val assembler = BoardLogLineAssembler()

        val first = assembler.append("[00:00:01.000,000] app: ".encodeToByteArray())
        val second = assembler.append("complete\r\n".encodeToByteArray())

        assertEquals(emptyList(), first)
        assertEquals(listOf("[00:00:01.000,000] app: complete"), second)
    }

    @Test
    fun append_hexDump_givesThreeLinesAndKeepsStartSpace() {
        val assembler = BoardLogLineAssembler()

        val lines = assembler.append(
            "[00:00:02.000,000] ble: packet\r\n 00 01 02 03\r\n 04 05 06 07\r\n".encodeToByteArray(),
        )

        assertEquals(
            listOf(
                "[00:00:02.000,000] ble: packet",
                " 00 01 02 03",
                " 04 05 06 07",
            ),
            lines,
        )
    }

    @Test
    fun append_hexLineChunkAfterOpenPartLine_continuesPartLine() {
        val assembler = BoardLogLineAssembler()

        val first = assembler.append("[00:00:02.000,000] ble: part".encodeToByteArray())
        val second = assembler.append(" 00 01 02\r\n".encodeToByteArray())

        assertEquals(emptyList(), first)
        assertEquals(listOf("[00:00:02.000,000] ble: part 00 01 02"), second)
    }

    @Test
    fun append_lfOnlyWithoutCr_givesOneLine() {
        val assembler = BoardLogLineAssembler()

        val lines = assembler.append("[00:00:03.000,000] app: lf only\n".encodeToByteArray())

        assertEquals(listOf("[00:00:03.000,000] app: lf only"), lines)
    }

    @Test
    fun append_emptyLineCrLf_givesNoLine() {
        val assembler = BoardLogLineAssembler()

        val lines = assembler.append("\r\n".encodeToByteArray())

        assertEquals(emptyList(), lines)
    }

    @Test
    fun append_lineOf600Characters_givesFullLine() {
        val assembler = BoardLogLineAssembler()
        val expected = "a".repeat(600)

        val lines = assembler.append("$expected\r\n".encodeToByteArray())

        assertEquals(listOf(expected), lines)
    }

    @Test
    fun append_splitUtf8CharacterBetweenChunks_decodesCorrectly() {
        val assembler = BoardLogLineAssembler()
        // U+00E9 (é) encodes to the two UTF-8 bytes 0xC3 and 0xA9.
        val firstChunk = "caf".encodeToByteArray() + byteArrayOf(0xC3.toByte())
        val secondChunk = byteArrayOf(0xA9.toByte()) + "\r\n".encodeToByteArray()

        val first = assembler.append(firstChunk)
        val second = assembler.append(secondChunk)

        assertEquals(emptyList(), first)
        assertEquals(listOf("café"), second)
    }

    @Test
    fun append_invalidUtf8Bytes_givesReplacementCharacter() {
        val assembler = BoardLogLineAssembler()
        val chunk = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), '\n'.code.toByte())

        val lines = assembler.append(chunk)

        assertEquals(listOf("a\uFFFDb"), lines)
    }

    @Test
    fun flush_withOpenPartLine_givesLineWithCutSuffixThenNull() {
        val assembler = BoardLogLineAssembler()
        assembler.append("[00:00:04.000,000] app: open".encodeToByteArray())

        val flushed = assembler.flush()
        val secondFlush = assembler.flush()

        assertEquals("[00:00:04.000,000] app: open${BoardLogLineAssembler.CUT_SUFFIX}", flushed)
        assertNull(secondFlush)
    }

    @Test
    fun flush_withEmptyBuffer_givesNull() {
        val assembler = BoardLogLineAssembler()

        assertNull(assembler.flush())
    }

    @Test
    fun append_cutLineThenTimestampChunk_givesCutFirstLineAndSecondLine() {
        val assembler = BoardLogLineAssembler()

        val first = assembler.append("[00:00:05.000,000] app: cut".encodeToByteArray())
        val second = assembler.append("[00:00:05.001,000] app: next\r\n".encodeToByteArray())

        assertEquals(emptyList(), first)
        assertEquals(
            listOf(
                "[00:00:05.000,000] app: cut${BoardLogLineAssembler.CUT_SUFFIX}",
                "[00:00:05.001,000] app: next",
            ),
            second,
        )
    }

    @Test
    fun append_cutLineThenDroppedChunk_givesCutFirstLineAndDroppedLine() {
        val assembler = BoardLogLineAssembler()

        val first = assembler.append("[00:00:06.000,000] app: cut".encodeToByteArray())
        val second = assembler.append("--- 3 messages dropped ---\r\n".encodeToByteArray())

        assertEquals(emptyList(), first)
        assertEquals(
            listOf(
                "[00:00:06.000,000] app: cut${BoardLogLineAssembler.CUT_SUFFIX}",
                "--- 3 messages dropped ---",
            ),
            second,
        )
    }

    @Test
    fun append_moreThanMaxPartLineBytesWithNoLf_givesOneLineWithCutSuffix() {
        val assembler = BoardLogLineAssembler()
        val payload = "x".repeat(BoardLogLineAssembler.MAX_PART_LINE_BYTES + 1)

        val lines = assembler.append(payload.encodeToByteArray())

        assertEquals(listOf("$payload${BoardLogLineAssembler.CUT_SUFFIX}"), lines)
        assertNull(assembler.flush())
    }
}
