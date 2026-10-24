package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OsManagementTest {

    private fun answering(response: ByteArray) = FakeSmpTransport().apply {
        onRequest = { request -> listOf(response.withSequenceOf(request)) }
    }

    @Test
    fun infoSendsTheFormatAndReturnsTheOutput() = runTest {
        val transport = answering(SmpFixtures.OS_INFO_RESPONSE)

        val output = OsManagement(SmpClient(transport)).info("i")

        assertContentEquals(SmpFixtures.OS_INFO_REQUEST, transport.writes.single())
        assertEquals("snapshot_v2/esp32c3", output)
    }

    @Test
    fun boardTargetReadsTheBoardTargetOfTheFirmware() = runTest {
        val transport = answering(SmpFixtures.OS_INFO_RESPONSE)

        val target = OsManagement(SmpClient(transport)).boardTarget()

        assertEquals(BoardTarget(board = "snapshot_v2", revision = null, qualifiers = "esp32c3"), target)
        assertContentEquals(SmpFixtures.OS_INFO_REQUEST, transport.writes.single())
    }

    @Test
    fun boardTargetReadsTheShortForm() = runTest {
        val target = OsManagement(SmpClient(answering(SmpFixtures.OS_INFO_RESPONSE_SHORT))).boardTarget()

        assertEquals(BoardTarget(board = "snapshot_v2", revision = null, qualifiers = null), target)
    }

    @Test
    fun boardTargetRefusesAnEmptyAnswer() = runTest {
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(responseTo(request, hex("BF 66 6F7574707574 60 FF"))) }
        }

        assertFailsWith<SmpMalformedFrame> { OsManagement(SmpClient(transport)).boardTarget() }
    }

    @Test
    fun anInvalidFormatIsAnOsGroupError() = runTest {
        // {"err": {"group": 0, "rc": 2}}: INVALID_FORMAT.
        val transport = FakeSmpTransport().apply {
            onRequest = { request -> listOf(responseTo(request, hex("BF 63 657272 BF 65 67726F7570 00 62 7263 02 FF FF"))) }
        }

        val error = assertFailsWith<SmpGroupError> { OsManagement(SmpClient(transport)).info("x") }

        assertEquals(OsManagementError.INVALID_FORMAT, error.osError)
        assertNull(error.imageError)
    }

    @Test
    fun resetSendsAnEmptyMapOrForce() = runTest {
        val transport = answering(SmpFixtures.OS_RESET_RESPONSE)
        val os = OsManagement(SmpClient(transport))

        os.reset()
        os.reset(force = true)

        assertContentEquals(SmpFixtures.OS_RESET_REQUEST, transport.requests[0].encode())
        val forced = transport.requests[1].encode().also { it[6] = 0 }
        assertContentEquals(SmpFixtures.OS_RESET_FORCE_REQUEST, forced)
    }

    @Test
    fun mcumgrParamsReadsTheBufferSize() = runTest {
        val transport = answering(SmpFixtures.OS_PARAMS_RESPONSE)

        val params = OsManagement(SmpClient(transport)).mcumgrParams()

        assertContentEquals(SmpFixtures.OS_PARAMS_REQUEST, transport.writes.single())
        assertEquals(McuMgrParams(bufferSize = 2475, bufferCount = 4), params)
    }

    @Test
    fun mcumgrParamsWithNoBufferSizeIsMalformed() = runTest {
        val transport = FakeSmpTransport().apply { onRequest = { request -> listOf(responseTo(request, hex("BF 69 6275665F636F756E74 04 FF"))) } }

        assertFailsWith<SmpMalformedFrame> { OsManagement(SmpClient(transport)).mcumgrParams() }
    }

    @Test
    fun mcumgrParamsOnABoardWithoutTheCommandIsENOTSUP() = runTest {
        val transport = answering(SmpFixtures.OS_PARAMS_NOT_SUPPORTED)

        val error = assertFailsWith<SmpReturnCodeError> { OsManagement(SmpClient(transport)).mcumgrParams() }

        assertEquals(McuMgrError.ENOTSUP, error.error)
    }

    @Test
    fun parsesEachFormOfTheHardwarePlatform() {
        assertEquals(BoardTarget("snapshot_v2", null, null), BoardTarget.parse("snapshot_v2"))
        assertEquals(BoardTarget("snapshot_v2", "2.1.0", null), BoardTarget.parse("snapshot_v2@2.1.0"))
        assertEquals(BoardTarget("snapshot_v2", null, "esp32c3"), BoardTarget.parse("snapshot_v2/esp32c3"))
        assertEquals(BoardTarget("snapshot_v2", "2.1.0", "esp32c3"), BoardTarget.parse("snapshot_v2@2.1.0/esp32c3"))
        assertEquals(BoardTarget("esp32c3_devkitm", null, "esp32c3"), BoardTarget.parse("esp32c3_devkitm/esp32c3"))
        assertEquals(BoardTarget("snapshot_v2", null, "esp32c3/cpu0"), BoardTarget.parse("snapshot_v2/esp32c3/cpu0"))
    }

    @Test
    fun theParseIgnoresSpacesAndALaterWord() {
        assertEquals("snapshot_v2", BoardTarget.parse("  snapshot_v2/esp32c3\n")?.board)
        assertEquals("snapshot_v2", BoardTarget.parse("snapshot_v2 Zephyr")?.board)
    }

    @Test
    fun theParseGivesNullWithNoBoardName() {
        assertNull(BoardTarget.parse(""))
        assertNull(BoardTarget.parse("   "))
        assertNull(BoardTarget.parse("/esp32c3"))
        assertNull(BoardTarget.parse("@1.0.0"))
    }
}
