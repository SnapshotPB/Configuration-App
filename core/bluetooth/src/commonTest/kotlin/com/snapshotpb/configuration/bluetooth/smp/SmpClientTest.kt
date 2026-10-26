package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmpClientTest {

    private val listResponseBody: ByteArray =
        SmpFixtures.IMAGE_LIST_RESPONSE.copyOfRange(SmpHeader.SIZE, SmpFixtures.IMAGE_LIST_RESPONSE.size)

    @Test
    fun sendsTheRequestAndReturnsTheResponseFrame() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { listOf(SmpFixtures.IMAGE_LIST_RESPONSE) } }
        val client = SmpClient(transport)

        val response = client.exchange(SmpOperation.Read, SmpGroup.IMAGE, ImageCommand.STATE, hex("A0"))

        assertContentEquals(SmpFixtures.IMAGE_LIST_REQUEST, transport.writes.single())
        assertEquals(SmpOperation.ReadResponse, response.header.operation)
        assertContentEquals(listResponseBody, response.body)
    }

    @Test
    fun joinsAResponseThatComesInSeveralNotifications() = runTest {
        // The board splits a response at MTU - 3: 20 bytes for MTU 23.
        val transport = FakeSmpTransport().apply { onRequest = { SmpFixtures.IMAGE_LIST_RESPONSE.pieces(20) } }

        val response = SmpClient(transport).exchange(SmpOperation.Read, SmpGroup.IMAGE, ImageCommand.STATE, hex("A0"))

        assertTrue(SmpFixtures.IMAGE_LIST_RESPONSE.size > 20 * 10)
        assertContentEquals(listResponseBody, response.body)
    }

    @Test
    fun theSequenceNumberGoesUpAndWrapsAfter255() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { request -> listOf(responseTo(request, hex("A0"))) } }
        val client = SmpClient(transport)

        repeat(257) { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }

        val sequences = transport.requests.map { it.header.sequence }
        assertEquals((0..255).toList() + 0, sequences)
    }

    @Test
    fun aRequestOverTheWriteLengthGoesOutInSeveralWrites() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = 20).apply { onRequest = { request -> listOf(responseTo(request, hex("A0"))) } }
        val body = SmpCbor.encode(ImageStateWriteWire.serializer(), ImageStateWriteWire(hash = hex(SLOT1_HASH_HEX), confirm = false))

        SmpClient(transport).exchange(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE, body)

        assertEquals(listOf(20, 20, 17), transport.writes.map { it.size })
        assertContentEquals(SmpFixtures.IMAGE_TEST_REQUEST, transport.requests.single().encode())
    }

    @Test
    fun timesOutWhenTheBoardDoesNotAnswer() = runTest {
        val transport = FakeSmpTransport()
        val client = SmpClient(transport, responseTimeoutMillis = 1_000L)

        val error = assertFailsWith<SmpTimeout> {
            client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.INFO, hex("A0"))
        }

        assertEquals(SmpGroup.OS, error.group)
        assertEquals(OsCommand.INFO, error.command)
        assertEquals(0, error.sequence)
    }

    @Test
    fun dropsTheLateAnswerToARequestThatTimedOut() = runTest {
        val transport = FakeSmpTransport()
        val client = SmpClient(transport, responseTimeoutMillis = 1_000L)
        assertFailsWith<SmpTimeout> { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.INFO, hex("A0")) }
        val late = transport.requests.single()
        transport.onRequest = { request ->
            listOf(responseTo(late, hex("A1 66 6F7574707574 63 6F6C64")), responseTo(request, hex("A0")))
        }

        val response = client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.INFO, hex("A0"))

        assertEquals(1, response.header.sequence)
        assertContentEquals(hex("A0"), response.body)
    }

    @Test
    fun dropsTheEndOfAResponseThatStartedBeforeTheRequest() = runTest {
        val transport = FakeSmpTransport()
        val tail = SmpFixtures.IMAGE_LIST_RESPONSE.copyOfRange(100, SmpFixtures.IMAGE_LIST_RESPONSE.size)
        transport.onRequest = { request -> listOf(tail, responseTo(request, hex("A0"))) }

        val response = SmpClient(transport).exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0"))

        assertContentEquals(hex("A0"), response.body)
    }

    @Test
    fun aSecondRequestWaitsForTheFirst() = runTest {
        val transport = FakeSmpTransport()
        val client = SmpClient(transport)
        val first = async { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }
        val second = async { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.INFO, hex("A0")) }
        testScheduler.runCurrent()

        assertEquals(1, transport.requests.size, "the second request waits for the answer to the first")
        transport.onRequest = { request -> listOf(responseTo(request, hex("A0"))) }
        transport.notify(responseTo(transport.requests.single(), hex("A0")))

        assertEquals(OsCommand.ECHO, first.await().header.command)
        assertEquals(OsCommand.INFO, second.await().header.command)
        assertEquals(listOf(0, 1), transport.requests.map { it.header.sequence })
    }

    @Test
    fun aWriteFailureEndsTheRequest() = runTest {
        val transport = FakeSmpTransport().apply { writeFailure = SmpTransportFailure("The link is closed") }

        assertFailsWith<SmpTransportFailure> {
            SmpClient(transport).exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0"))
        }
    }

    @Test
    fun aFrameThatStopsBetweenItsWritesBlocksEachLaterRequest() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = 20)
        var writes = 0
        val failing = object : SmpTransport by transport {
            override suspend fun write(value: ByteArray) {
                if (++writes == 2) throw SmpTransportFailure("The link is closed")
                transport.write(value)
            }
        }
        val client = SmpClient(failing)
        val body = SmpCbor.encode(ImageStateWriteWire.serializer(), ImageStateWriteWire(hash = hex(SLOT1_HASH_HEX), confirm = false))
        assertFailsWith<SmpTransportFailure> { client.exchange(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE, body) }
        transport.onRequest = { request -> listOf(responseTo(request, hex("A0"))) }

        assertFailsWith<SmpTransportFailure> { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }
        assertEquals(1, transport.writes.size, "the later request sends nothing")
    }

    @Test
    fun aFailureOfTheFirstWriteDoesNotBlockTheNextRequest() = runTest {
        val transport = FakeSmpTransport().apply { writeFailure = SmpTransportFailure("The link is closed") }
        val client = SmpClient(transport)
        assertFailsWith<SmpTransportFailure> { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }
        transport.writeFailure = null
        transport.onRequest = { request -> listOf(responseTo(request, hex("A0"))) }

        assertEquals(1, client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")).header.sequence)
    }

    @Test
    fun aFailureOfTheFirstWriteOfASplitFrameBlocksEachLaterRequest() = runTest {
        // The failed write can be on the board already, so the board can hold part of a frame.
        val transport = FakeSmpTransport(maxWriteLength = 20).apply { writeFailure = SmpTransportFailure("Timed out") }
        val client = SmpClient(transport)
        assertFailsWith<SmpTransportFailure> { client.exchange(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE, ByteArray(30)) }
        transport.writeFailure = null
        transport.onRequest = { request -> listOf(responseTo(request, hex("A0"))) }

        assertFailsWith<SmpTransportFailure> { client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }
        assertEquals(0, transport.writes.size, "the later request sends nothing")
    }

    @Test
    fun anUnsupportedWriteDoesNotBlockTheNextRequest() = runTest {
        val transport = FakeSmpTransport(maxWriteLength = 20).apply { writeFailure = SmpWriteUnsupported() }
        val client = SmpClient(transport)
        assertFailsWith<SmpWriteUnsupported> { client.exchange(SmpOperation.Write, SmpGroup.IMAGE, ImageCommand.STATE, ByteArray(30)) }
        transport.writeFailure = null
        transport.onRequest = { request -> listOf(responseTo(request, hex("A0"))) }

        assertEquals(1, client.exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")).header.sequence)
    }

    @Test
    fun anEndOfTheNotificationFlowIsATransportFailure() = runTest {
        val transport = FakeSmpTransport()
        val ended = object : SmpTransport by transport {
            override val notifications = emptyFlow<ByteArray>()
        }

        assertFailsWith<SmpTransportFailure> { SmpClient(ended).exchange(SmpOperation.Read, SmpGroup.OS, OsCommand.ECHO, hex("A0")) }
    }

    @Test
    fun aV2GroupErrorBecomesSmpGroupError() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(SmpFixtures.UPLOAD_ERROR_NO_FREE_SLOT.withSequenceOf(request)) }
        }

        val error = assertFailsWith<SmpGroupError> {
            ImageManagement(SmpClient(transport)).upload(ByteArray(100), hex(FILE_SHA_HEX))
        }

        assertEquals(SmpGroup.IMAGE, error.errorGroup)
        assertEquals(ImageManagementError.NO_FREE_SLOT, error.imageError)
        assertEquals(null, error.osError)
    }

    @Test
    fun aV1AnswerWithAnRcBecomesSmpReturnCodeError() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(SmpFixtures.UPLOAD_ERROR_V1_EINVAL.withSequenceOf(request)) }
        }

        val error = assertFailsWith<SmpReturnCodeError> {
            ImageManagement(SmpClient(transport)).upload(ByteArray(100), hex(FILE_SHA_HEX))
        }

        assertEquals(McuMgrError.EINVAL, error.error)
        assertEquals(McuMgrError.EINVAL.code, error.rc)
    }

    @Test
    fun anRcOfZeroIsNotAnError() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { request -> listOf(responseTo(request, hex("BF 62 7263 00 FF"))) } }

        ImageManagement(SmpClient(transport)).erase()
    }
}
