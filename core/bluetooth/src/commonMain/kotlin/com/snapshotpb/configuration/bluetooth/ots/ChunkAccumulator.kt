package com.snapshotpb.configuration.bluetooth.ots

/**
 * Reassembles an OTS object's content from the chunks notified on the vendor
 * [OtsUuids.ObjectData] characteristic.
 *
 * Standard OTS streams object content over an L2CAP connection-oriented
 * channel, where the channel framing and the OACP length parameter together
 * signal "transfer complete". This vendor transport has neither, so completion
 * is inferred purely from the Object Size metadata read before the transfer:
 * the accumulator is [isComplete] once exactly [expectedSize] bytes have
 * arrived.
 *
 * Pure and I/O-free so the reassembly / completion logic can be unit-tested
 * without a live GATT connection. [OtsClient.readSelectedObjectBytes] feeds it
 * from the real notification flow.
 *
 * @param expectedSize the Object Size (current size) read from metadata; the
 *   exact number of content bytes the firmware is expected to notify.
 */
internal class ChunkAccumulator(private val expectedSize: Long) {

    private val buffer = ArrayList<Byte>(expectedSize.coerceAtMost(MAX_PREALLOC).toInt())

    /** Number of content bytes accumulated so far. */
    val received: Int get() = buffer.size

    /** True once exactly [expectedSize] bytes have been accumulated. */
    val isComplete: Boolean get() = received.toLong() == expectedSize

    /**
     * True once at least [expectedSize] bytes have arrived — i.e. the firmware
     * has notified everything (or, on [isOverrun], more than) the metadata
     * promised, so the collector can stop waiting.
     */
    val isDone: Boolean get() = received.toLong() >= expectedSize

    /** True if the firmware notified strictly more bytes than [expectedSize]. */
    val isOverrun: Boolean get() = received.toLong() > expectedSize

    /** Appends one notified content chunk. */
    fun append(chunk: ByteArray) {
        for (b in chunk) buffer.add(b)
    }

    /**
     * Returns the reassembled content.
     *
     * @throws OtsTransferIncomplete if fewer than [expectedSize] bytes arrived,
     *   or if an overrun produced more bytes than promised.
     */
    fun result(): ByteArray {
        if (!isComplete) throw OtsTransferIncomplete(expectedSize, received)
        return buffer.toByteArray()
    }

    private companion object {
        /** Cap on pre-allocation so a bogus huge Object Size can't OOM us. */
        const val MAX_PREALLOC = 64L * 1024
    }
}
