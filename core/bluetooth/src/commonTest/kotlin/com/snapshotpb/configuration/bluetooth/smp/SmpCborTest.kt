package com.snapshotpb.configuration.bluetooth.smp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the CBOR that the codec writes against bytes that were written by hand (see
 * [SmpFixtures]), and the decode of the forms that the board writes.
 */
class SmpCborTest {

    private fun frame(operation: SmpOperation, group: Int, command: Int, sequence: Int, body: ByteArray): ByteArray =
        SmpFrame(SmpHeader(operation, length = body.size, group = group, sequence = sequence, command = command), body).encode()

    @Test
    fun theFirstUploadChunkIsADefiniteMapWithByteStrings() {
        val body = SmpCbor.encode(
            ImageUploadRequestWire.serializer(),
            ImageManagement.uploadRequest(hex("3DB8F396"), offset = 0L, imageSize = 4096L, sha256 = hex(FILE_SHA_HEX)),
        )

        assertEquals(0xA5.toByte(), body[0], "a definite map of 5 pairs, which zcbor accepts also when canonical")
        assertContentEquals(SmpFixtures.UPLOAD_FIRST_REQUEST, frame(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.UPLOAD, 1, body))
    }

    @Test
    fun aLaterUploadChunkHasOnlyOffAndData() {
        val body = SmpCbor.encode(
            ImageUploadRequestWire.serializer(),
            ImageManagement.uploadRequest(hex("AABB"), offset = 65_536L, imageSize = 4096L, sha256 = hex(FILE_SHA_HEX)),
        )

        assertContentEquals(SmpFixtures.UPLOAD_NEXT_REQUEST, frame(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.UPLOAD, 2, body))
    }

    @Test
    fun integersUseTheShortestHead() {
        val offsets = listOf(23L to "17", 24L to "18 18", 255L to "18 FF", 256L to "19 0100", 65_535L to "19 FFFF", 65_536L to "1A 00010000")
        for ((offset, head) in offsets) {
            val body = SmpCbor.encode(ImageUploadRequestWire.serializer(), ImageUploadRequestWire(off = offset, data = ByteArray(0)))
            assertContentEquals(hex("A2 63 6F6666 $head 64 64617461 40"), body, "off $offset")
        }
    }

    @Test
    fun anEmptyRequestIsAnEmptyDefiniteMap() {
        assertContentEquals(hex("A0"), SmpCbor.encode(EmptyWire.serializer(), EmptyWire()))
    }

    @Test
    fun decodesAnIndefiniteLengthImageList() {
        val body = SmpFixtures.IMAGE_LIST_RESPONSE.copyOfRange(SmpHeader.SIZE, SmpFixtures.IMAGE_LIST_RESPONSE.size)

        val state = SmpCbor.decode(ImageStateWire.serializer(), body)

        assertEquals(2, state.images.size)
        val (slot0, slot1) = state.images
        assertEquals(0, slot0.slot)
        assertEquals("1.2.3", slot0.version)
        assertContentEquals(hex(SLOT0_HASH_HEX), slot0.hash)
        assertTrue(slot0.bootable && slot0.confirmed && slot0.active)
        assertFalse(slot0.pending || slot0.permanent)
        assertEquals(1, slot1.slot)
        assertEquals("1.3.0", slot1.version)
        assertTrue(slot1.pending)
        assertFalse(slot1.confirmed || slot1.active)
    }

    @Test
    fun decodesACanonicalFrugalImageListWithAbsentFlagsAsFalse() {
        val bytes = SmpFixtures.IMAGE_LIST_RESPONSE_CANONICAL
        val state = SmpCbor.decode(ImageStateWire.serializer(), bytes.copyOfRange(SmpHeader.SIZE, bytes.size))

        val slot = state.images.single()
        assertTrue(slot.bootable && slot.confirmed && slot.active)
        assertFalse(slot.pending || slot.permanent)
        assertEquals(0, slot.image)
    }

    @Test
    fun ignoresAnUnknownKeyWithANestedValue() {
        // {"off": 7, "extra": {"a": [1, 2]}, "match": false}, indefinite lengths.
        val body = hex("BF 63 6F6666 07 65 6578747261 BF 61 61 9F 01 02 FF FF 65 6D61746368 F4 FF")

        val answer = SmpCbor.decode(ImageUploadResponseWire.serializer(), body)

        assertEquals(7L, answer.off)
        assertEquals(false, answer.match)
    }

    @Test
    fun decodesTheUploadAnswers() {
        fun body(frame: ByteArray) = frame.copyOfRange(SmpHeader.SIZE, frame.size)

        val next = SmpCbor.decode(ImageUploadResponseWire.serializer(), body(SmpFixtures.UPLOAD_RESPONSE_OFF_256))
        assertEquals(256L, next.off)
        assertNull(next.match, "only the last answer of a board with the image check has match")

        val done = SmpCbor.decode(ImageUploadResponseWire.serializer(), body(SmpFixtures.UPLOAD_RESPONSE_DONE_MATCH))
        assertEquals(4096L, done.off)
        assertEquals(true, done.match)
    }

    @Test
    fun readsBothErrorForms() {
        val v2 = SmpFixtures.UPLOAD_ERROR_NO_FREE_SLOT
        val v2Status = SmpCbor.decode(SmpStatusWire.serializer(), v2.copyOfRange(SmpHeader.SIZE, v2.size))
        assertEquals(SmpGroup.IMAGE, v2Status.err?.group)
        assertEquals(ImageManagementError.NO_FREE_SLOT.code, v2Status.err?.rc)
        assertNull(v2Status.rc)

        val v1 = SmpFixtures.UPLOAD_ERROR_V1_EINVAL
        val v1Status = SmpCbor.decode(SmpStatusWire.serializer(), v1.copyOfRange(SmpHeader.SIZE, v1.size))
        assertEquals(McuMgrError.EINVAL.code, v1Status.rc)
        assertNull(v1Status.err)
    }

    @Test
    fun anEmptyBodyDecodesAsAnEmptyMap() {
        val answer = SmpCbor.decode(ImageUploadResponseWire.serializer(), ByteArray(0))
        assertNull(answer.off)
    }

    @Test
    fun aTruncatedBodyIsMalformed() {
        val body = SmpFixtures.OS_INFO_RESPONSE.copyOfRange(SmpHeader.SIZE, SmpFixtures.OS_INFO_RESPONSE.size - 4)
        assertFailsWith<SmpMalformedFrame> { SmpCbor.decode(OsInfoResponseWire.serializer(), body) }
    }

    @Test
    fun aWrongTypeIsMalformed() {
        // {"off": "x"}
        assertFailsWith<SmpMalformedFrame> { SmpCbor.decode(ImageUploadResponseWire.serializer(), hex("A1 63 6F6666 61 78")) }
    }

    @Test
    fun headLengthFollowsRfc8949() {
        assertEquals(1, SmpCbor.headLength(0))
        assertEquals(1, SmpCbor.headLength(23))
        assertEquals(2, SmpCbor.headLength(24))
        assertEquals(2, SmpCbor.headLength(255))
        assertEquals(3, SmpCbor.headLength(256))
        assertEquals(3, SmpCbor.headLength(65_535))
        assertEquals(5, SmpCbor.headLength(65_536))
        assertEquals(9, SmpCbor.headLength(0x1_0000_0000L))
    }
}
