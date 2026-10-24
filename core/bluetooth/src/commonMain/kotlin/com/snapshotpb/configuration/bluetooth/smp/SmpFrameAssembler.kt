package com.snapshotpb.configuration.bluetooth.smp

import com.toxicbakery.logging.Arbor

/**
 * Joins the pieces of SMP frames. The board sends a response in pieces of `MTU - 3` bytes
 * (`smp_bt_tx_pkt()`), and only the first piece has the header. The length in the header gives the
 * size of the frame, so the assembler knows when a frame is complete.
 *
 * With [responses] true, the assembler accepts only a server frame (a response operation, no flags).
 * It drops the bytes that it holds when they do not start with such a header. This removes the end
 * of a response that started before the collection, which would otherwise block each later frame.
 * A board-side fake uses [responses] false to join the pieces of a request.
 *
 * One instance is for one collector. It is not safe for concurrent use.
 */
internal class SmpFrameAssembler(private val responses: Boolean = true) {
    private var pending = ByteArray(0)

    /** Adds [piece] and returns the frames that are now complete, in order. */
    fun append(piece: ByteArray): List<SmpFrame> {
        pending += piece
        val frames = mutableListOf<SmpFrame>()
        while (pending.size >= SmpHeader.SIZE) {
            val header = plausibleHeader() ?: run {
                log.d { "Dropping ${pending.size} bytes that do not start with an SMP header" }
                pending = ByteArray(0)
                return frames
            }
            val total = SmpHeader.SIZE + header.length
            if (pending.size < total) break
            frames += SmpFrame(header, pending.copyOfRange(SmpHeader.SIZE, total))
            pending = pending.copyOfRange(total, pending.size)
        }
        return frames
    }

    private fun plausibleHeader(): SmpHeader? {
        val header = try {
            SmpHeader.decode(pending)
        } catch (malformed: SmpMalformedFrame) {
            log.d { "Not an SMP header: ${malformed.message}" }
            return null
        }
        if (pending[0].toInt() and RESERVED_BITS != 0 || header.flags != 0) return null
        if (header.operation.isResponse != responses) return null
        return header
    }

    private companion object {
        /** Bits 5 to 7 of the first header byte. SMP keeps them at zero. */
        const val RESERVED_BITS = 0xE0
        val log = Arbor.tag("SmpFrameAssembler")
    }
}
