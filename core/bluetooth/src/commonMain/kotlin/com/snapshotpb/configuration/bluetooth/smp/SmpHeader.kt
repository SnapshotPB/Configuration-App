package com.snapshotpb.configuration.bluetooth.smp

/**
 * The operation of an SMP frame (`mcumgr_op_t`). It is in bits 0 to 2 of the first header byte.
 */
enum class SmpOperation(val code: Int) {
    Read(0),
    ReadResponse(1),
    Write(2),
    WriteResponse(3);

    /** True for the two operations that the server sends. */
    val isResponse: Boolean get() = this == ReadResponse || this == WriteResponse

    /** The operation of the response to a request with this operation. */
    val response: SmpOperation
        get() = when (this) {
            Read, ReadResponse -> ReadResponse
            Write, WriteResponse -> WriteResponse
        }

    companion object {
        /** The operation with [code], or null for the codes 4 to 7, which SMP does not define. */
        fun fromCode(code: Int): SmpOperation? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The protocol version in bits 3 and 4 of the first header byte. A [V2] server reports a group
 * error as an `err` map. A [V1] server reports each error as one `rc` value. A server answers with
 * the version of the request, or with a lower version that it knows.
 */
enum class SmpVersion(val code: Int) {
    V1(0),
    V2(1);

    companion object {
        /** The version with [code], or null for the codes 2 and 3, which SMP keeps for later use. */
        fun fromCode(code: Int): SmpVersion? = entries.firstOrNull { it.code == code }
    }
}

/** The SMP group IDs that this client uses (`mcumgr_group_t`). */
object SmpGroup {
    const val OS: Int = 0
    const val IMAGE: Int = 1
}

/** The command IDs of the OS group (`os_mgmt.h`). */
object OsCommand {
    const val ECHO: Int = 0
    const val RESET: Int = 5
    const val MCUMGR_PARAMS: Int = 6
    const val INFO: Int = 7
}

/** The command IDs of the image group (`img_mgmt.h`). */
object ImageCommand {
    const val STATE: Int = 0
    const val UPLOAD: Int = 1
    const val ERASE: Int = 5
}

/**
 * The 8-byte header of an SMP frame. All fields of more than one byte are big-endian:
 *
 * | Byte | Content                                                  |
 * |------|----------------------------------------------------------|
 * | 0    | bits 0-2 operation, bits 3-4 version, bits 5-7 reserved  |
 * | 1    | flags (no flags are defined; always 0)                   |
 * | 2-3  | length of the CBOR body                                  |
 * | 4-5  | group ID                                                 |
 * | 6    | sequence number                                          |
 * | 7    | command ID                                               |
 */
data class SmpHeader(
    val operation: SmpOperation,
    val version: SmpVersion = SmpVersion.V2,
    val flags: Int = 0,
    val length: Int,
    val group: Int,
    val sequence: Int,
    val command: Int,
) {
    init {
        require(flags in 0..BYTE_MAX) { "flags $flags is not one byte" }
        require(length in 0..SHORT_MAX) { "length $length is not two bytes" }
        require(group in 0..SHORT_MAX) { "group $group is not two bytes" }
        require(sequence in 0..BYTE_MAX) { "sequence $sequence is not one byte" }
        require(command in 0..BYTE_MAX) { "command $command is not one byte" }
    }

    /** The 8 bytes of this header. */
    fun encode(): ByteArray = byteArrayOf(
        ((version.code shl VERSION_SHIFT) or operation.code).toByte(),
        flags.toByte(),
        (length shr BYTE_BITS).toByte(),
        length.toByte(),
        (group shr BYTE_BITS).toByte(),
        group.toByte(),
        sequence.toByte(),
        command.toByte(),
    )

    companion object {
        /** The size of the header in bytes (`MGMT_HDR_SIZE`). */
        const val SIZE: Int = 8

        private const val BYTE_BITS = 8
        private const val BYTE_MAX = 0xFF
        private const val SHORT_MAX = 0xFFFF
        private const val OPERATION_MASK = 0x07
        private const val VERSION_SHIFT = 3
        private const val VERSION_MASK = 0x03

        /**
         * Reads a header from the 8 bytes of [bytes] at [offset]. The reserved bits are ignored.
         *
         * @throws SmpMalformedFrame when fewer than 8 bytes follow [offset], or when the operation
         *   or the version has a code that SMP does not define.
         */
        fun decode(bytes: ByteArray, offset: Int = 0): SmpHeader {
            if (offset < 0 || bytes.size - offset < SIZE) {
                throw SmpMalformedFrame("An SMP header needs $SIZE bytes, but ${bytes.size - offset} follow")
            }
            val first = bytes.unsignedAt(offset)
            val operation = SmpOperation.fromCode(first and OPERATION_MASK)
                ?: throw SmpMalformedFrame("Unknown SMP operation ${first and OPERATION_MASK}")
            val version = SmpVersion.fromCode((first shr VERSION_SHIFT) and VERSION_MASK)
                ?: throw SmpMalformedFrame("Unknown SMP version ${(first shr VERSION_SHIFT) and VERSION_MASK}")
            return SmpHeader(
                operation = operation,
                version = version,
                flags = bytes.unsignedAt(offset + 1),
                length = (bytes.unsignedAt(offset + 2) shl BYTE_BITS) or bytes.unsignedAt(offset + 3),
                group = (bytes.unsignedAt(offset + 4) shl BYTE_BITS) or bytes.unsignedAt(offset + 5),
                sequence = bytes.unsignedAt(offset + 6),
                command = bytes.unsignedAt(offset + 7),
            )
        }

        private fun ByteArray.unsignedAt(index: Int): Int = this[index].toInt() and BYTE_MAX
    }
}

/** One SMP frame: a [header] and its CBOR [body]. The header length is the body size. */
class SmpFrame(val header: SmpHeader, val body: ByteArray) {
    init {
        require(header.length == body.size) { "The header length ${header.length} is not the body size ${body.size}" }
    }

    /** The header bytes, then the body bytes. */
    fun encode(): ByteArray = header.encode() + body

    /** True when this frame is the response to the request with the header [request]. */
    fun answers(request: SmpHeader): Boolean =
        header.operation == request.operation.response &&
            header.group == request.group &&
            header.command == request.command &&
            header.sequence == request.sequence

    override fun toString(): String = "SmpFrame($header, ${body.size} body bytes)"
}
