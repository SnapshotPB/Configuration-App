package com.snapshotpb.configuration.bluetooth.smp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmpHeaderTest {

    @Test
    fun encodesAV2WriteWithTheVersionBitsAndBigEndianFields() {
        val header = SmpHeader(
            operation = SmpOperation.Write,
            length = 0x0144,
            group = 0x0102,
            sequence = 0xFE,
            command = ImageCommand.UPLOAD,
        )

        assertContentEquals(hex("0A 00 0144 0102 FE 01"), header.encode())
    }

    @Test
    fun roundTripsEachOperationAndVersion() {
        for (operation in SmpOperation.entries) {
            for (version in SmpVersion.entries) {
                val header = SmpHeader(operation, version, flags = 0, length = 0xFFFF, group = 0xFFFF, sequence = 0xFF, command = 0xFF)
                assertEquals(header, SmpHeader.decode(header.encode()))
            }
        }
    }

    @Test
    fun decodesAV1ResponseHeader() {
        val header = SmpHeader.decode(SmpFixtures.UPLOAD_ERROR_V1_EINVAL)

        assertEquals(SmpOperation.WriteResponse, header.operation)
        assertEquals(SmpVersion.V1, header.version)
        assertEquals(6, header.length)
        assertEquals(SmpGroup.IMAGE, header.group)
        assertEquals(1, header.sequence)
        assertEquals(ImageCommand.UPLOAD, header.command)
    }

    @Test
    fun decodesAtAnOffsetAndIgnoresTheReservedBits() {
        val bytes = hex("FF FF" + "E9 00 0015 0000 07 07")

        val header = SmpHeader.decode(bytes, offset = 2)

        assertEquals(SmpOperation.ReadResponse, header.operation)
        assertEquals(SmpVersion.V2, header.version)
        assertEquals(0x15, header.length)
        assertEquals(7, header.sequence)
    }

    @Test
    fun refusesAShortHeader() {
        assertFailsWith<SmpMalformedFrame> { SmpHeader.decode(hex("08 00 0001 0001 00")) }
    }

    @Test
    fun refusesAnUndefinedOperation() {
        assertFailsWith<SmpMalformedFrame> { SmpHeader.decode(hex("0C 00 0000 0000 00 00")) }
    }

    @Test
    fun refusesAReservedVersion() {
        assertFailsWith<SmpMalformedFrame> { SmpHeader.decode(hex("10 00 0000 0000 00 00")) }
    }

    @Test
    fun refusesAFieldOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            SmpHeader(SmpOperation.Read, length = 0x1_0000, group = 0, sequence = 0, command = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SmpHeader(SmpOperation.Read, length = 0, group = 0, sequence = 256, command = 0)
        }
    }

    @Test
    fun aResponseAnswersOnlyItsOwnRequest() {
        val request = SmpHeader(SmpOperation.Read, length = 1, group = SmpGroup.IMAGE, sequence = 0, command = ImageCommand.STATE)
        val response = SmpFrame(SmpHeader.decode(SmpFixtures.IMAGE_LIST_RESPONSE), SmpFixtures.IMAGE_LIST_RESPONSE.copyOfRange(8, SmpFixtures.IMAGE_LIST_RESPONSE.size))

        assertTrue(response.answers(request))
        assertFalse(response.answers(request.copy(sequence = 1)))
        assertFalse(response.answers(request.copy(operation = SmpOperation.Write)))
        assertFalse(response.answers(request.copy(command = ImageCommand.UPLOAD)))
    }
}
