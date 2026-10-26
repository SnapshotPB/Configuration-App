package com.snapshotpb.configuration.bluetooth.smp

import com.snapshotpb.configuration.bluetooth.FakeBlueFalconEngine
import com.snapshotpb.configuration.bluetooth.FakeBluetoothPeripheral
import com.snapshotpb.configuration.bluetooth.FakeBluetoothService
import com.snapshotpb.configuration.bluetooth.FakeOtsCharacteristic
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class BlueFalconSmpTransportTest {

    private class Fixture(
        val engine: FakeBlueFalconEngine,
        val blueFalcon: BlueFalcon,
        val characteristic: FakeOtsCharacteristic,
        val peripheral: FakeBluetoothPeripheral,
    )

    private fun TestScope.fixture(mtu: Int? = null): Fixture {
        val engine = FakeBlueFalconEngine(backgroundScope)
        val characteristic = FakeOtsCharacteristic(SmpUuids.Characteristic)
        val peripheral = FakeBluetoothPeripheral(
            uuid = "board",
            mtuSize = mtu,
            services = listOf(FakeBluetoothService(SmpUuids.Service, characteristics = listOf(characteristic))),
        )
        return Fixture(engine, BlueFalcon(engine), characteristic, peripheral)
    }

    private val writeKey = CharacteristicWriteKey("board", CharacteristicWriteType.WithoutResponse)

    private fun capability(ready: Boolean) =
        mapOf(writeKey to CharacteristicWriteCapability(maximumLength = 244, ready = ready, supported = true))

    private suspend fun Fixture.open(): BlueFalconSmpTransport =
        assertIs<SmpOpenResult.Opened>(BlueFalconSmpTransport.open(blueFalcon, peripheral) {}).transport

    @Test
    fun aBoardWithoutTheSmpServiceGivesNoSmpService() = runTest {
        val f = fixture()
        val oldBoard = FakeBluetoothPeripheral(uuid = "old", services = listOf(FakeBluetoothService()))

        assertSame(SmpOpenResult.NoSmpService, BlueFalconSmpTransport.open(f.blueFalcon, oldBoard) {})
        assertEquals(emptyList(), f.engine.notifyCalls)
    }

    @Test
    fun aServiceWithoutTheCharacteristicThrows() = runTest {
        val f = fixture()
        val broken = FakeBluetoothPeripheral(uuid = "broken", services = listOf(FakeBluetoothService(SmpUuids.Service)))

        assertFailsWith<SmpCharacteristicMissing> { BlueFalconSmpTransport.open(f.blueFalcon, broken) {} }
    }

    @Test
    fun openSubscribesAndWaitsForTheCcc() = runTest {
        val f = fixture()
        val subscribed = mutableListOf<BluetoothCharacteristic>()

        val result = BlueFalconSmpTransport.open(f.blueFalcon, f.peripheral) { subscribed += it }

        assertIs<SmpOpenResult.Opened>(result)
        assertEquals(listOf<Pair<BluetoothCharacteristic, Boolean>>(f.characteristic to true), f.engine.notifyCalls)
        assertEquals(listOf<BluetoothCharacteristic>(f.characteristic), subscribed)
        result.transport.close()
        assertEquals(f.characteristic to false, f.engine.notifyCalls.last())
    }

    @Test
    fun aFailedSubscriptionWaitRemovesTheSubscription() = runTest {
        val f = fixture()

        assertFailsWith<IllegalStateException> {
            BlueFalconSmpTransport.open(f.blueFalcon, f.peripheral) { error("no CCC") }
        }
        assertEquals(f.characteristic to false, f.engine.notifyCalls.last())
    }

    @Test
    fun writesWithoutResponse() = runTest {
        val f = fixture()
        val types = mutableListOf<CharacteristicWriteType>()
        f.engine.onTypedWrite = { _, _, type -> types += type; CharacteristicWriteResult.Sent }

        f.open().write(hex("0A00"))

        assertEquals(listOf(CharacteristicWriteType.WithoutResponse), types)
    }

    @Test
    fun aWriteWaitsWhileTheEngineReportsTheWriteTypeAsNotReady() = runTest {
        val f = fixture()
        f.engine.writeCapabilities.value = capability(ready = false)
        var attempts = 0
        f.engine.onTypedWrite = { _, _, _ -> attempts++; CharacteristicWriteResult.Sent }
        val transport = f.open()

        val write = async { transport.write(hex("0A00")) }
        delay(10)
        assertEquals(0, attempts, "no write while the engine reports not ready")
        f.engine.writeCapabilities.value = capability(ready = true)
        write.await()

        assertEquals(1, attempts)
    }

    @Test
    fun aBackpressuredWriteWaitsUntilTheEngineIsReadyAndWritesAgain() = runTest {
        // As IosEngine: the Backpressured result also sets the ready state to false.
        val f = fixture()
        f.engine.writeCapabilities.value = capability(ready = true)
        var attempts = 0
        f.engine.onTypedWrite = { _, _, _ ->
            attempts++
            if (f.engine.writeCapabilities.value[writeKey]?.ready == true && attempts > 1) {
                CharacteristicWriteResult.Sent
            } else {
                f.engine.writeCapabilities.value = capability(ready = false)
                CharacteristicWriteResult.Backpressured
            }
        }
        val transport = f.open()

        val write = async { transport.write(hex("0A00")) }
        delay(100)
        assertEquals(1, attempts, "the second attempt waits for the ready state")
        f.engine.writeCapabilities.value = capability(ready = true)
        write.await()

        assertEquals(2, attempts)
    }

    @Test
    fun aStackWithNoRoomForTheTimeLimitIsATransportFailure() = runTest {
        val f = fixture()
        f.engine.onTypedWrite = { _, _, _ -> CharacteristicWriteResult.Backpressured }
        val transport = f.open()

        var failed = false
        val failedBeforeTheLimit = async { delay(BlueFalconSmpTransport.BACKPRESSURE_LIMIT_MILLIS - 1); failed }

        assertFailsWith<SmpTransportFailure> { transport.write(hex("0A00")) }
        failed = true
        assertEquals(false, failedBeforeTheLimit.await(), "the transport tries until the time limit")
    }

    @Test
    fun aBackpressuredWriteWithNoEngineStateWaitsBeforeItWritesAgain() = runTest {
        val f = fixture()
        var attempts = 0
        f.engine.onTypedWrite = { _, _, _ ->
            if (++attempts < 3) CharacteristicWriteResult.Backpressured else CharacteristicWriteResult.Sent
        }
        val transport = f.open()
        // Two waits of 20 ms come before the third attempt, so at 39 ms only two attempts ran.
        val attemptsAt39Millis = async { delay(39); attempts }

        transport.write(hex("0A00"))

        assertEquals(3, attempts)
        assertEquals(2, attemptsAt39Millis.await())
    }

    @Test
    fun aCancelledSubscriptionWaitRemovesTheSubscription() = runTest {
        val f = fixture()
        val open = async { BlueFalconSmpTransport.open(f.blueFalcon, f.peripheral) { awaitCancellation() } }
        delay(10)

        open.cancel()
        open.join()

        assertEquals(f.characteristic to false, f.engine.notifyCalls.last())
    }

    @Test
    fun anEngineWithoutTheTypedWriteFailsAtOnceAndDoesNotUseTheOlderCall() = runTest {
        // JsEngine and MacosJvmEngine: the default of the engine interface is Unsupported.
        val f = fixture()
        val legacy = mutableListOf<ByteArray>()
        f.engine.onWriteCharacteristic = { _, value -> legacy += value }
        val transport = f.open()

        assertFailsWith<SmpWriteUnsupported> { transport.write(hex("0A00 0001")) }
        assertEquals(emptyList(), legacy)
    }

    @Test
    fun aClosedLinkIsATransportFailure() = runTest {
        val f = fixture()
        f.engine.onTypedWrite = { _, _, _ -> CharacteristicWriteResult.Disconnected }

        assertFailsWith<SmpTransportFailure> { f.open().write(hex("00")) }
    }

    @Test
    fun aPayloadOverTheLimitIsATransportFailure() = runTest {
        val f = fixture()
        f.engine.onTypedWrite = { _, _, _ -> CharacteristicWriteResult.PayloadTooLarge(20) }

        assertFailsWith<SmpTransportFailure> { f.open().write(ByteArray(30)) }
    }

    @Test
    fun theWriteLengthComesFromTheEngineThenTheMtu() = runTest {
        val f = fixture(mtu = 498)
        val transport = f.open()

        assertEquals(495, transport.maxWriteLength)
        f.engine.maximumWriteLength = 182
        assertEquals(182, transport.maxWriteLength)
    }

    @Test
    fun withNoMtuTheWriteLengthIsForTheMinimumMtu() = runTest {
        assertEquals(20, fixture(mtu = null).open().maxWriteLength)
    }

    @Test
    fun anEngineLimitOfZeroFallsBackToTheMtu() = runTest {
        val f = fixture(mtu = 247)
        f.engine.maximumWriteLength = 0

        assertEquals(244, f.open().maxWriteLength)
    }

    @Test
    fun theWriteLengthIsNeverOverTheLargestAttributeValue() = runTest {
        val f = fixture(mtu = 517)
        val transport = f.open()

        assertEquals(512, transport.maxWriteLength)
        f.engine.maximumWriteLength = 514
        assertEquals(512, transport.maxWriteLength)
    }

    @Test
    fun notificationsComeFromTheCharacteristic() = runTest {
        val f = fixture()
        val transport = f.open()
        val first = async(start = CoroutineStart.UNDISPATCHED) { transport.notifications.first() }

        f.characteristic.emitter.emit(hex("0B00"))

        assertContentEquals(hex("0B00"), first.await())
    }
}
