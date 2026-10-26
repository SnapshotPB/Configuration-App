package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageManagementTest {

    private val sha = hex(FILE_SHA_HEX)

    /** An image of [size] bytes with a different value at each offset. */
    private fun image(size: Int): ByteArray = ByteArray(size) { index -> (index * 7 + index / 251).toByte() }

    private fun boardOn(transport: FakeSmpTransport, board: FakeImageBoard) {
        transport.onRequest = { request -> board.answer(request) }
    }

    // --- State, test, confirm, erase -----------------------------------------------------------

    @Test
    fun listSendsAnEmptyReadAndDecodesBothSlots() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { listOf(SmpFixtures.IMAGE_LIST_RESPONSE) } }

        val slots = ImageManagement(SmpClient(transport)).list()

        assertContentEquals(SmpFixtures.IMAGE_LIST_REQUEST, transport.writes.single())
        assertEquals(
            ImageSlot(
                image = 0, slot = 0, version = "1.2.3", hash = ImageHash(hex(SLOT0_HASH_HEX)),
                bootable = true, pending = false, confirmed = true, active = true, permanent = false,
            ),
            slots[0],
        )
        assertEquals(ImageHash(hex(SLOT1_HASH_HEX)), slots[1].hash)
        assertTrue(slots[1].pending)
    }

    @Test
    fun testSendsTheHashWithConfirmFalse() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(SmpFixtures.IMAGE_LIST_RESPONSE_CANONICAL.withSequenceOf(request).withOperation(SmpOperation.WriteResponse)) }
        }

        val slots = ImageManagement(SmpClient(transport)).test(ImageHash(hex(SLOT1_HASH_HEX)))

        assertContentEquals(SmpFixtures.IMAGE_TEST_REQUEST, transport.requests.single().encode())
        assertEquals(1, slots.size)
    }

    @Test
    fun confirmWithNoHashSendsOnlyConfirmTrue() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { request -> listOf(responseTo(request, hex("BF 66 696D61676573 9F FF FF"))) } }

        val slots = ImageManagement(SmpClient(transport)).confirm()

        assertContentEquals(SmpFixtures.IMAGE_CONFIRM_REQUEST, transport.writes.single())
        assertEquals(emptyList(), slots)
    }

    @Test
    fun eraseSendsTheSlotOnlyWhenGiven() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { request -> listOf(responseTo(request, hex("BF FF"))) } }
        val images = ImageManagement(SmpClient(transport))

        images.erase()
        images.erase(slot = 1)

        assertContentEquals(SmpFixtures.IMAGE_ERASE_REQUEST, transport.requests[0].encode())
        assertContentEquals(
            SmpFixtures.IMAGE_ERASE_SLOT1_REQUEST.withSequence(1),
            transport.requests[1].encode(),
        )
    }

    @Test
    fun imageHashComparesByContentAndPrintsHex() {
        assertEquals(ImageHash(hex("01AB")), ImageHash(hex("01AB")))
        assertEquals(ImageHash(hex("01AB")).hashCode(), ImageHash(hex("01AB")).hashCode())
        assertEquals("01ab", ImageHash(hex("01AB")).toHex())
    }

    // --- Upload --------------------------------------------------------------------------------

    @Test
    fun uploadsTheWholeImageInOneWritePerRequest() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = maxWriteLengthForMtu(247))
        val board = FakeImageBoard()
        boardOn(transport, board)
        val data = image(2_000)
        val progress = mutableListOf<UploadProgress>()

        val result = ImageManagement(SmpClient(transport)).upload(data, sha, onProgress = { progress += it })

        assertEquals(UploadResult(match = null), result, "the board firmware has no image check")
        assertContentEquals(data, board.written)
        assertEquals(listOf(0L), board.firstChunkOffsets)
        assertEquals(transport.requests.size, transport.writes.size, "each request is one ATT write")
        assertTrue(transport.writes.all { it.size <= 244 })
        assertEquals(UploadProgress(2_000L, 2_000L), progress.last())
        assertEquals(board.requestOffsets.drop(1), progress.dropLast(1).map { it.offset })
    }

    @Test
    fun continuesFromALowerOffsetWhenTheBoardLosesTheUpload() = runTest {
        val transport = FakeSmpTransport()
        val board = FakeImageBoard(loseStateAt = 1_000L)
        boardOn(transport, board)
        val data = image(2_000)

        val result = ImageManagement(SmpClient(transport)).upload(data, sha)

        assertEquals(UploadResult(match = null), result)
        assertContentEquals(data, board.written)
        assertEquals(listOf(0L, 0L), board.firstChunkOffsets, "the upload starts again at offset 0 with len and sha")
    }

    @Test
    fun aSecondCallWithTheSameShaResumesAtTheOffsetOfTheBoard() = runTest {
        val transport = FakeSmpTransport()
        val board = FakeImageBoard()
        var answered = 0
        transport.onRequest = { request ->
            board.answer(request).also { if (++answered == 3) transport.writeFailure = SmpTransportFailure("The link is closed") }
        }
        val images = ImageManagement(SmpClient(transport))
        val data = image(3_000)
        assertFailsWith<SmpTransportFailure> { images.upload(data, sha) }
        val reached = board.requestOffsets.size
        transport.writeFailure = null

        val result = images.upload(data, sha)

        assertEquals(UploadResult(match = null), result)
        assertContentEquals(data, board.written)
        val resumed = board.requestOffsets.drop(reached)
        assertEquals(0L, resumed[0], "the resume sends the first chunk again")
        assertTrue(resumed[1] > 0L, "the board answers with its offset, and the next chunk starts there")
        assertEquals(listOf(0L, 0L), board.firstChunkOffsets)
    }

    @Test
    fun aRefusedChunkStopsTheUploadWithTheGroupError() = runTest {
        val transport = FakeSmpTransport()
        boardOn(transport, FakeImageBoard(refuseAt = 0L, refusal = ImageManagementError.NO_FREE_SLOT))

        val error = assertFailsWith<SmpGroupError> { ImageManagement(SmpClient(transport)).upload(image(1_000), sha) }

        assertEquals(ImageManagementError.NO_FREE_SLOT, error.imageError)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aRefusalInTheMiddleStopsTheUpload() = runTest {
        val transport = FakeSmpTransport()
        val board = FakeImageBoard(refuseAt = null)
        boardOn(transport, board)
        val images = ImageManagement(SmpClient(transport))
        val data = image(2_000)
        // The second offset of a normal upload is the offset where the refusal goes.
        images.upload(data, sha)
        val second = board.requestOffsets[1]
        val refusing = FakeImageBoard(refuseAt = second, refusal = ImageManagementError.FLASH_WRITE_FAILED)
        boardOn(transport, refusing)

        val error = assertFailsWith<SmpGroupError> { images.upload(data, sha) }

        assertEquals(ImageManagementError.FLASH_WRITE_FAILED, error.imageError)
        assertEquals(listOf(0L, second), refusing.requestOffsets)
    }

    @Test
    fun aV1ErrorStopsTheUploadWithTheReturnCode() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(SmpFixtures.UPLOAD_ERROR_V1_EINVAL.withSequenceOf(request)) }
        }

        val error = assertFailsWith<SmpReturnCodeError> { ImageManagement(SmpClient(transport)).upload(image(500), sha) }

        assertEquals(McuMgrError.EINVAL, error.error)
    }

    @Test
    fun aBoardThatDoesNotMoveItsOffsetStallsTheUpload() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(responseTo(request, SmpCbor.encode(ImageUploadResponseWire.serializer(), ImageUploadResponseWire(off = 0L)))) }
        }

        val error = assertFailsWith<SmpUploadStalled> { ImageManagement(SmpClient(transport)).upload(image(500), sha) }

        assertEquals(0L, error.boardOffset)
        assertEquals(5, transport.requests.size)
    }

    @Test
    fun aBoardThatMovesForwardAndBackAgainAndAgainStopsTheUpload() = runTest {
        // Each answer moves the offset forward by 100 bytes, then back to 0.
        var forward = true
        val transport = FakeSmpTransport().apply {
            onRequest = { request ->
                val off = if (forward) 100L else 0L
                forward = !forward
                listOf(responseTo(request, SmpCbor.encode(ImageUploadResponseWire.serializer(), ImageUploadResponseWire(off = off))))
            }
        }

        assertFailsWith<SmpUploadStalled> { ImageManagement(SmpClient(transport)).upload(image(1_000), sha) }
    }

    @Test
    fun aMatchOfFalseIsAnError() = runTest {
        val data = image(100)
        val transport = FakeSmpTransport().apply {
            onRequest = { request ->
                listOf(responseTo(request, SmpCbor.encode(ImageUploadResponseWire.serializer(), ImageUploadResponseWire(off = 100L, match = false))))
            }
        }

        assertFailsWith<SmpImageMismatch> { ImageManagement(SmpClient(transport)).upload(data, sha) }
    }

    @Test
    fun aBoardWithoutTheImageCheckGivesNoMatch() = runTest {
        val transport = FakeSmpTransport()
        boardOn(transport, FakeImageBoard(checkImage = false))

        assertNull(ImageManagement(SmpClient(transport)).upload(image(800), sha).match)
    }

    @Test
    fun aBoardWithTheImageCheckGivesMatchTrue() = runTest {
        val transport = FakeSmpTransport()
        boardOn(transport, FakeImageBoard(checkImage = true))

        assertEquals(UploadResult(match = true), ImageManagement(SmpClient(transport)).upload(image(800), sha))
    }

    @Test
    fun anAnswerOutsideTheImageIsMalformed() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(responseTo(request, SmpCbor.encode(ImageUploadResponseWire.serializer(), ImageUploadResponseWire(off = 501L)))) }
        }

        assertFailsWith<SmpMalformedFrame> { ImageManagement(SmpClient(transport)).upload(image(500), sha) }
    }

    @Test
    fun aKnownBoardBufferGivesFramesOfThatSizeInWritesOfTheMtu() = runTest {
        // The bench firmware: MTU 498 and a buffer of 2475 bytes, five writes of 495 bytes.
        val transport = FakeSmpTransport(maxWriteLength = maxWriteLengthForMtu(498), boardBufferSize = 2_475)
        val board = FakeImageBoard()
        boardOn(transport, board)
        val data = image(20_000)

        ImageManagement(SmpClient(transport)).upload(data, sha, boardBufferSize = 2_475)

        assertContentEquals(data, board.written)
        val frames = transport.requests.map { it.encode().size }
        assertTrue(frames.dropLast(1).all { it == 2_475 }, "each request but the last fills the buffer: $frames")
        assertTrue(transport.writes.all { it.size <= 495 })
        assertEquals(5, transport.writes.size / (transport.requests.size - 1), "five writes for each full request")
        assertEquals(9, transport.requests.size)
    }

    @Test
    fun withNoBoardBufferEachRequestIsOneWriteOfTheDefaultBuffer() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = maxWriteLengthForMtu(498))
        val board = FakeImageBoard()
        boardOn(transport, board)
        val data = image(5_000)

        ImageManagement(SmpClient(transport)).upload(data, sha)

        assertContentEquals(data, board.written)
        assertEquals(transport.requests.size, transport.writes.size, "each request is one ATT write")
        assertEquals(ImageManagement.DEFAULT_BOARD_BUFFER_SIZE, transport.writes.first().size)
    }

    @Test
    fun aKnownBoardBufferBelowOneWriteGivesOneWritePerRequest() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = maxWriteLengthForMtu(498), boardBufferSize = 300)
        val board = FakeImageBoard()
        boardOn(transport, board)
        val data = image(3_000)

        ImageManagement(SmpClient(transport)).upload(data, sha, boardBufferSize = 300)

        assertContentEquals(data, board.written)
        assertEquals(transport.requests.size, transport.writes.size)
        assertEquals(300, transport.writes.first().size)
    }

    @Test
    fun onASmallMtuEachRequestFillsTheBoardBufferInSeveralWrites() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = maxWriteLengthForMtu(23))
        val board = FakeImageBoard()
        boardOn(transport, board)
        val data = image(1_000)

        ImageManagement(SmpClient(transport)).upload(data, sha, boardBufferSize = 384)

        assertContentEquals(data, board.written)
        assertTrue(transport.writes.all { it.size <= 20 })
        assertTrue(transport.requests.all { it.encode().size <= 384 })
        assertEquals(384, transport.requests.first().encode().size, "the first request fills the buffer")
    }

    @Test
    fun uploadRefusesABadArgument() = runTest {
        val images = ImageManagement(SmpClient(FakeSmpTransport()))
        assertFailsWith<IllegalArgumentException> { images.upload(ByteArray(0), sha) }
        assertFailsWith<IllegalArgumentException> { images.upload(image(10), ByteArray(20)) }
    }

    // --- Chunk size ----------------------------------------------------------------------------

    /**
     * The data per request for each ATT MTU and board buffer, for an image of 900 000 bytes. A later
     * chunk at an offset of 65 536 or more costs 23 bytes (8 header + 15 map), plus the byte string
     * head. The first chunk costs 73 bytes. A known buffer gives frames of that size. With no known
     * buffer (null), the frame is one write of up to 384 bytes; MTU 23 and 65 cannot carry the
     * 32-byte image header in the first chunk of one write, so their frames have 384 bytes.
     */
    @Test
    fun chunkLengthForEachMtu() {
        val size = 900_000L
        data class Case(val mtu: Int, val buffer: Int?, val frame: Int, val first: Int, val later: Int)
        val cases = listOf(
            Case(mtu = 23, buffer = null, frame = 384, first = 308, later = 358),
            Case(mtu = 65, buffer = null, frame = 384, first = 308, later = 358),
            Case(mtu = 185, buffer = null, frame = 182, first = 107, later = 157),
            Case(mtu = 247, buffer = null, frame = 244, first = 169, later = 219),
            Case(mtu = 498, buffer = null, frame = 384, first = 308, later = 358),
            Case(mtu = 517, buffer = null, frame = 384, first = 308, later = 358),
            Case(mtu = 23, buffer = 2475, frame = 2475, first = 2399, later = 2449),
            Case(mtu = 185, buffer = 2475, frame = 2475, first = 2399, later = 2449),
            Case(mtu = 498, buffer = 2475, frame = 2475, first = 2399, later = 2449),
            Case(mtu = 498, buffer = 384, frame = 384, first = 308, later = 358),
            Case(mtu = 498, buffer = 300, frame = 300, first = 225, later = 274),
        )
        for (case in cases) {
            val frame = ImageManagement.uploadFrameBudget(maxWriteLengthForMtu(case.mtu), case.buffer, size, sha)
            assertEquals(case.frame, frame, "frame of $case")
            assertEquals(case.first, ImageManagement.uploadChunkLength(frame, 0L, size, sha), "first chunk of $case")
            assertEquals(case.later, ImageManagement.uploadChunkLength(frame, 65_536L, size, sha), "later chunk of $case")
        }
    }

    /** The computed length fills the frame: one more byte would not fit. */
    @Test
    fun chunkLengthIsTheLargestThatFits() {
        val size = 900_000L
        for (budget in 80..700) {
            for (offset in listOf(0L, 5L, 100L, 1_000L, 70_000L)) {
                val length = ImageManagement.uploadChunkLength(budget, offset, size, sha)
                fun frameSize(n: Int) = SmpHeader.SIZE +
                    SmpCbor.encode(ImageUploadRequestWire.serializer(), ImageManagement.uploadRequest(ByteArray(n), offset, size, sha)).size
                assertTrue(frameSize(length) <= budget, "budget $budget offset $offset length $length")
                assertTrue(frameSize(length + 1) > budget, "budget $budget offset $offset length ${length + 1}")
            }
        }
    }

    @Test
    fun aBoardBufferThatCannotCarryTheImageHeaderIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            ImageManagement.uploadFrameBudget(maxWriteLengthForMtu(23), boardBufferSize = 64, imageSize = 900_000L, sha256 = sha)
        }
    }
}

/** [this] frame with the sequence number [sequence]. */
private fun ByteArray.withSequence(sequence: Int): ByteArray = copyOf().also { it[6] = sequence.toByte() }

/** [this] frame with the operation [operation], and the version bits kept. */
private fun ByteArray.withOperation(operation: SmpOperation): ByteArray =
    copyOf().also { it[0] = ((it[0].toInt() and 0xF8) or operation.code).toByte() }
