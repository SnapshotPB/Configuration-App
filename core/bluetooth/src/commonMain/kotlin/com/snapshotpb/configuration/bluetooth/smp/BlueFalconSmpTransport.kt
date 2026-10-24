package com.snapshotpb.configuration.bluetooth.smp

import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The [SmpTransport] of a connected [peripheral] on BlueFalcon. It writes without response to the
 * SMP characteristic and reads its notifications.
 *
 * A write uses the typed `writeCharacteristic` of BlueFalcon 3.7, which reports each result. The
 * engines of BlueFalcon 3.7.14 differ:
 *
 * - `AndroidEngine` suspends each write until the stack accepts it. It reports `Backpressured` when
 *   its GATT queue is busy, and it keeps the ready state in `characteristicWriteCapabilities`.
 * - `IosEngine` reports `Backpressured` when CoreBluetooth cannot take a write without response
 *   (`canSendWriteWithoutResponse`), and sets the ready state again when it can.
 * - `WindowsEngine` and `RpiEngine` return each write when the stack sent it. They report no ready
 *   state and no write length.
 * - `MacosJvmEngine` and `JsEngine` have no typed write (`Unsupported`). Their older write call
 *   gives no result and no backpressure: `MacosJvmEngine` writes with response when the type is
 *   not 1, and `JsEngine` does not wait for its `writeValue()`, so two writes in sequence collide.
 *   Thus the transport does not use the older call. It throws [SmpWriteUnsupported] at the first
 *   write, and the caller can tell the user that the update is not available on that platform.
 *
 * Before each write and after each `Backpressured` result, the transport waits while the engine
 * reports the write type as not ready. When the engine reports no state, the wait is a short fixed
 * delay. So a frame of several writes does not overflow the queue of the stack.
 *
 * Lifecycle: make one instance for each link with [open], after the services are discovered, and
 * call [close] when the link closes or the update ends.
 */
class BlueFalconSmpTransport private constructor(
    private val blueFalcon: BlueFalcon,
    private val peripheral: BluetoothPeripheral,
    private val characteristic: BluetoothCharacteristic,
) : SmpTransport {

    override val notifications: Flow<ByteArray> get() = characteristic.notifications

    private val writeKey = CharacteristicWriteKey(peripheral.uuid, CharacteristicWriteType.WithoutResponse)

    /**
     * The limit that the engine reports for a write without response. When the engine reports none
     * (or zero), the value is the MTU of the peripheral minus 3. With no MTU either, the value is
     * for the minimum MTU, 20 bytes. The value is never more than [MAX_ATTRIBUTE_LENGTH]: Android
     * refuses a longer value, also when the MTU is 517.
     *
     * `RpiEngine` reports no MTU, so a link on Linux uses writes of 20 bytes. The Apple engines set
     * `mtuSize` to the limit of a write with response (often 512), not to an ATT MTU, so the MTU
     * fallback is too large there. `IosEngine` reports its limit for each connection, so that
     * fallback does not apply to it.
     */
    override val maxWriteLength: Int
        get() {
            val length = blueFalcon.maximumWriteValueLength(peripheral, CharacteristicWriteType.WithoutResponse)
                ?.takeIf { it > 0 }
                ?: peripheral.mtuSize?.takeIf { it >= DEFAULT_ATT_MTU }?.let(::maxWriteLengthForMtu)
                ?: maxWriteLengthForMtu(DEFAULT_ATT_MTU)
            return minOf(length, MAX_ATTRIBUTE_LENGTH)
        }

    /**
     * @throws SmpWriteUnsupported when the engine has no typed write.
     * @throws SmpTransportFailure when the write fails, when the link is closed, or when the stack
     *   has no room for the write for [BACKPRESSURE_LIMIT_MILLIS].
     */
    override suspend fun write(value: ByteArray) {
        withTimeoutOrNull(BACKPRESSURE_LIMIT_MILLIS) { writeUntilSent(value) }
            ?: throw SmpTransportFailure("The stack had no room for a write without response for $BACKPRESSURE_LIMIT_MILLIS ms")
    }

    /** Writes [value] again after each `Backpressured` result, until the engine reports it as sent. */
    private suspend fun writeUntilSent(value: ByteArray) {
        while (true) {
            awaitWriteReady()
            val result = blueFalcon.writeCharacteristic(
                peripheral, characteristic, value, CharacteristicWriteType.WithoutResponse,
            )
            when (result) {
                CharacteristicWriteResult.Sent -> return
                CharacteristicWriteResult.Backpressured -> backOff()
                CharacteristicWriteResult.Unsupported -> throw SmpWriteUnsupported()
                is CharacteristicWriteResult.PayloadTooLarge ->
                    throw SmpTransportFailure("The write of ${value.size} bytes is over the limit of ${result.maximumLength}")
                CharacteristicWriteResult.Disconnected -> throw SmpTransportFailure("The link is closed")
                is CharacteristicWriteResult.Failed -> throw SmpTransportFailure("The SMP write failed", result.cause)
            }
        }
    }

    /** Removes the notification subscription. A failure is logged only: the link can be closed. */
    suspend fun close() {
        try {
            blueFalcon.notifyCharacteristic(peripheral, characteristic, notify = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log.d { "The SMP notification subscription did not close: ${error.message}" }
        }
    }

    /**
     * Waits while the engine reports the write type as not ready, up to [READY_WAIT_MILLIS]. The
     * engine keeps that state in a StateFlow, so the wait cannot miss a change that came before it.
     * When the engine reports no state, or reports the write type as ready, this does not wait.
     */
    private suspend fun awaitWriteReady() {
        if (blueFalcon.characteristicWriteCapabilities.value[writeKey]?.ready != false) return
        withTimeoutOrNull(READY_WAIT_MILLIS) {
            blueFalcon.characteristicWriteCapabilities.first { capabilities -> capabilities[writeKey]?.ready != false }
        }
    }

    /**
     * Waits after a `Backpressured` result. When the engine now reports the write type as not
     * ready, the next [awaitWriteReady] waits for the change. Else this is a fixed delay, so the loop
     * does not write again at once.
     */
    private suspend fun backOff() {
        if (blueFalcon.characteristicWriteCapabilities.value[writeKey]?.ready != false) {
            delay(BACKPRESSURE_DELAY_MILLIS)
        }
    }

    companion object {
        /**
         * The largest value of an attribute (Core specification, Vol 3, Part F, 3.2.9). A write
         * without response can carry `MTU - 3` bytes, which is 514 at the MTU 517.
         */
        const val MAX_ATTRIBUTE_LENGTH: Int = 512

        /**
         * The time limit of one write while the stack has no room. A flash erase on the board can
         * stop the radio for some hundred milliseconds, so this leaves much margin.
         */
        const val BACKPRESSURE_LIMIT_MILLIS: Long = 10_000L

        /** The time limit of the cleanup in [open]: a closed link must not hold a cancelled caller. */
        private const val CLOSE_LIMIT_MILLIS = 2_000L
        private const val READY_WAIT_MILLIS = 250L
        private const val BACKPRESSURE_DELAY_MILLIS = 20L
        private val log = Arbor.tag("BlueFalconSmpTransport")

        /**
         * Finds the SMP characteristic on [peripheral], subscribes to its notifications, and waits in
         * [awaitSubscribed] until the stack has written the CCC (see
         * `PlatformBluetoothSeams.awaitSubscribed`).
         *
         * A board on a firmware without the update service has no SMP service. The result is then
         * [SmpOpenResult.NoSmpService], so the app can tell the user that the board needs a USB
         * flash. Use [hasSmpService] to know this before the open.
         *
         * The services must be discovered first, and the SMP service must have its characteristics.
         * Some engines supply the characteristics only after `discoverCharacteristics`
         * (`MacosJvmEngine`).
         *
         * @throws SmpCharacteristicMissing when the service has no SMP characteristic.
         */
        suspend fun open(
            blueFalcon: BlueFalcon,
            peripheral: BluetoothPeripheral,
            awaitSubscribed: suspend (BluetoothCharacteristic) -> Unit,
        ): SmpOpenResult {
            val service = findSmpService(peripheral) ?: return SmpOpenResult.NoSmpService
            val characteristic = service.characteristics.firstOrNull { it.uuid == SmpUuids.Characteristic }
                ?: throw SmpCharacteristicMissing(SmpUuids.Characteristic.toString())
            val transport = BlueFalconSmpTransport(blueFalcon, peripheral, characteristic)
            try {
                blueFalcon.notifyCharacteristic(peripheral, characteristic, notify = true)
                awaitSubscribed(characteristic)
            } catch (error: Throwable) {
                // Do not leave a subscription that no transport owns, also after a cancellation.
                // Rethrow the first failure.
                withContext(NonCancellable) { withTimeoutOrNull(CLOSE_LIMIT_MILLIS) { transport.close() } }
                throw error
            }
            return SmpOpenResult.Opened(transport)
        }
    }
}

/** The result of [BlueFalconSmpTransport.open]. */
sealed interface SmpOpenResult {
    /** The board has the SMP service, and [transport] is subscribed to its notifications. */
    class Opened(val transport: BlueFalconSmpTransport) : SmpOpenResult

    /** The board has no SMP service: its firmware has no update service and needs a USB flash. */
    data object NoSmpService : SmpOpenResult
}
