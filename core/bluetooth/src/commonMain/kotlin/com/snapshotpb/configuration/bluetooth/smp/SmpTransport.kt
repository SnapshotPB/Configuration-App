package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.coroutines.flow.Flow

/**
 * The link to the SMP characteristic of one board. [SmpClient] uses only this seam, so a test can
 * give it a fake board. [BlueFalconSmpTransport] is the implementation on BlueFalcon.
 */
interface SmpTransport {
    /**
     * The value of each notification on the SMP characteristic, in the order of arrival. The flow is
     * hot and does not replay: [SmpClient] collects it before it writes a request. A collector must
     * be subscribed when the collection first suspends, so use a SharedFlow with no `buffer`,
     * `flowOn` or `callbackFlow` in front of it. Else a response can come before the subscription.
     */
    val notifications: Flow<ByteArray>

    /**
     * The number of bytes that one write without response can carry now. This is the ATT MTU minus
     * 3, or a lower limit that the platform reports.
     */
    val maxWriteLength: Int

    /**
     * Sends [value] as one write without response. [value] is not longer than [maxWriteLength].
     * The function returns when the stack took the write. When the stack has no room, it waits, so
     * that [SmpClient] can send the writes of a frame one after the other.
     *
     * @throws SmpWriteUnsupported when the platform cannot write without response with a result.
     * @throws SmpTransportFailure when the write fails or the link is closed.
     */
    suspend fun write(value: ByteArray)
}

/** The ATT MTU of a link before an MTU exchange. */
const val DEFAULT_ATT_MTU: Int = 23

/** The bytes of an ATT write command that are not the value: the opcode and the handle. */
private const val ATT_WRITE_OVERHEAD = 3

/** The length of the value of one ATT write without response on a link with the ATT MTU [mtu]. */
fun maxWriteLengthForMtu(mtu: Int): Int {
    require(mtu >= DEFAULT_ATT_MTU) { "The ATT MTU cannot be less than $DEFAULT_ATT_MTU: $mtu" }
    return mtu - ATT_WRITE_OVERHEAD
}
