package com.snapshotpb.configuration.bluetooth.smp

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * A client for the MCUmgr Simple Management Protocol over one [SmpTransport].
 *
 * The client sends SMP v2 frames. It has one request in flight: a second call waits until the first
 * gets its response or fails. Each request has the next sequence number (0 to 255, then 0 again).
 * The client collects the notifications before it writes the request, joins the pieces of the
 * response, and takes the first frame that answers the request. It drops a frame with a different
 * sequence number, for example the late response to a request that timed out.
 *
 * A request that is longer than [SmpTransport.maxWriteLength] goes out in several writes. The board
 * joins them only with `CONFIG_MCUMGR_TRANSPORT_BT_REASSEMBLY`, and the full frame must fit in
 * `CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE` bytes. When such a request stops after some of its writes (a
 * failed write or a cancellation, also of its first write), the board can hold part of a frame, and it adds the next bytes to
 * that part. The client then refuses each later request with [SmpTransportFailure]. Close the link
 * and make a new client. The board drops the part when the subscription to the notifications ends
 * (`smp_bt_ccc_changed()`), and the stack ends it at each disconnect.
 *
 * Each write goes out only after the write before it returned, so the backpressure of
 * [SmpTransport.write] applies to each piece of a frame.
 *
 * The time limit of a request starts after its last write, so a slow link with many writes for
 * each frame does not use up the time limit of the response.
 *
 * [ImageManagement] and [OsManagement] give the commands of the image group and the OS group.
 */
class SmpClient(
    private val transport: SmpTransport,
    private val responseTimeoutMillis: Long = DEFAULT_RESPONSE_TIMEOUT_MILLIS,
) {
    private val requestMutex = Mutex()

    /** The sequence number of the next request. Read and written only under [requestMutex]. */
    private var nextSequence = 0

    /**
     * True after a request frame stopped between two of its writes. The board then holds part of a
     * frame, so no later request can go on this link. Read and written only under [requestMutex].
     */
    private var outOfStep = false

    /** The number of bytes that one write without response can carry now. */
    val maxWriteLength: Int get() = transport.maxWriteLength

    /**
     * Sends one request frame with [body] and returns the response frame. The response can hold an
     * error map: this function does not read the body.
     *
     * @throws SmpTimeout when no response comes in [timeoutMillis] after the last write.
     * @throws SmpTransportFailure when a write fails, when the notification flow ends, or when an
     *   earlier request stopped between two of its writes.
     */
    suspend fun exchange(
        operation: SmpOperation,
        group: Int,
        command: Int,
        body: ByteArray,
        timeoutMillis: Long = responseTimeoutMillis,
    ): SmpFrame = requestMutex.withLock {
        if (outOfStep) {
            throw SmpTransportFailure("An earlier SMP request stopped between two writes; open a new link")
        }
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and SEQUENCE_MASK
        val header = SmpHeader(
            operation = operation,
            length = body.size,
            group = group,
            sequence = sequence,
            command = command,
        )
        val request = SmpFrame(header, body).encode()
        log.d { "Request group $group command $command sequence $sequence (${request.size} bytes)" }
        coroutineScope {
            val assembler = SmpFrameAssembler()
            // UNDISPATCHED: the collector is on the hot notification flow before the first write.
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                transport.notifications
                    .transform { piece -> assembler.append(piece).forEach { emit(it) } }
                    .firstOrNull { frame ->
                        frame.answers(header).also { answers ->
                            if (!answers) log.d { "Dropping $frame: it does not answer sequence $sequence" }
                        }
                    }
            }
            try {
                writeFrame(request)
            } catch (error: Throwable) {
                response.cancel()
                throw error
            }
            val answer = withTimeoutOrNull(timeoutMillis) {
                response.await() ?: throw SmpTransportFailure("The SMP notification flow ended")
            }
            if (answer == null) {
                response.cancel()
                throw SmpTimeout(group, command, sequence)
            }
            answer
        }
    }

    /**
     * Sends [request] as the CBOR body of a request and decodes the response body with
     * [responseDeserializer].
     *
     * @throws SmpGroupError when the response has an `err` map with a code that is not zero.
     * @throws SmpReturnCodeError when the response has an `rc` that is not zero.
     * @throws SmpMalformedFrame when the response body is not CBOR of the expected shape.
     */
    internal suspend fun <Q, R> request(
        operation: SmpOperation,
        group: Int,
        command: Int,
        requestSerializer: SerializationStrategy<Q>,
        request: Q,
        responseDeserializer: DeserializationStrategy<R>,
        timeoutMillis: Long = responseTimeoutMillis,
    ): R {
        val response = exchange(operation, group, command, SmpCbor.encode(requestSerializer, request), timeoutMillis)
        throwOnError(group, command, response.body)
        return SmpCbor.decode(responseDeserializer, response.body)
    }

    /**
     * Writes [frame] in pieces of [SmpTransport.maxWriteLength] bytes. When the writes stop before
     * the last piece of a frame with several pieces, [outOfStep] becomes true. This applies also to a
     * failure of the first piece: a cancelled or timed-out write can be on the board already. Only
     * [SmpWriteUnsupported] is known to send nothing.
     */
    private suspend fun writeFrame(frame: ByteArray) {
        val pieceLength = transport.maxWriteLength
        if (pieceLength <= 0) throw SmpTransportFailure("The transport reports a write length of $pieceLength")
        var offset = 0
        try {
            while (offset < frame.size) {
                val end = minOf(offset + pieceLength, frame.size)
                transport.write(frame.copyOfRange(offset, end))
                offset = end
            }
        } catch (error: Throwable) {
            if (offset > 0 || (frame.size > pieceLength && error !is SmpWriteUnsupported)) {
                outOfStep = true
                log.d { "The SMP request stopped after $offset of ${frame.size} bytes" }
            }
            throw error
        }
    }

    companion object {
        /**
         * The time limit for one response. A chunk of an upload includes a flash write, and the
         * board answers in tens of milliseconds, so this leaves much margin.
         */
        const val DEFAULT_RESPONSE_TIMEOUT_MILLIS: Long = 5_000L

        private const val SEQUENCE_MASK = 0xFF
        private val log = Arbor.tag("SmpClient")

        /**
         * Reads the two error forms of [body]. The board can use the `rc` form also in v2: a
         * refusal of an application hook with `MGMT_CB_ERROR_RC` writes `rc`, and a server that
         * does not know v2 answers in v1.
         */
        internal fun throwOnError(group: Int, command: Int, body: ByteArray) {
            val status = SmpCbor.decode(SmpStatusWire.serializer(), body)
            val err = status.err
            if (err != null && err.rc != 0) throw SmpGroupError(group, command, err.group, err.rc)
            val rc = status.rc
            if (rc != null && rc != 0) throw SmpReturnCodeError(group, command, rc)
        }
    }
}
