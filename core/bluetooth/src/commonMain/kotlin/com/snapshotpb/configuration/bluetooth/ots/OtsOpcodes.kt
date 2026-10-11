package com.snapshotpb.configuration.bluetooth.ots

/** Object Action Control Point opcodes (OTS spec §3.11.2). */
object OacpOpcode {
    const val CREATE: Byte = 0x01
    const val DELETE: Byte = 0x02
    const val CALCULATE_CHECKSUM: Byte = 0x03
    const val EXECUTE: Byte = 0x04
    const val READ: Byte = 0x05
    const val WRITE: Byte = 0x06
    const val ABORT: Byte = 0x07
    const val RESPONSE_CODE: Byte = 0x60.toByte()
}

/** OACP response codes (OTS spec §3.11.3). */
enum class OacpResult(val code: Byte) {
    Success(0x01),
    OpCodeNotSupported(0x02),
    InvalidParameter(0x03),
    InsufficientResources(0x04),
    InvalidObject(0x05),
    ChannelUnavailable(0x06),
    UnsupportedType(0x07),
    ProcedureNotPermitted(0x08),
    ObjectLocked(0x09),
    OperationFailed(0x0A);

    companion object {
        fun fromCode(code: Byte): OacpResult? = entries.firstOrNull { it.code == code }
    }
}

/** Object List Control Point opcodes (OTS spec §3.12.2). */
object OlcpOpcode {
    const val FIRST: Byte = 0x01
    const val LAST: Byte = 0x02
    const val PREVIOUS: Byte = 0x03
    const val NEXT: Byte = 0x04
    const val GOTO: Byte = 0x05
    const val ORDER: Byte = 0x06
    const val REQUEST_NUMBER_OF_OBJECTS: Byte = 0x07
    const val CLEAR_MARKING: Byte = 0x08
    const val RESPONSE_CODE: Byte = 0x70.toByte()
}

/** OLCP response codes (OTS spec §3.12.3). */
enum class OlcpResult(val code: Byte) {
    Success(0x01),
    OpCodeNotSupported(0x02),
    InvalidParameter(0x03),
    OperationFailed(0x04),
    OutOfBounds(0x05),
    TooManyObjects(0x06),
    NoObject(0x07),
    ObjectIdNotFound(0x08);

    companion object {
        fun fromCode(code: Byte): OlcpResult? = entries.firstOrNull { it.code == code }
    }
}

/** Object Properties bitmask (OTS spec §3.6). 32-bit little-endian field. */
object ObjectPropertyBit {
    const val DELETE = 1 shl 0
    const val EXECUTE = 1 shl 1
    const val READ = 1 shl 2
    const val WRITE = 1 shl 3
    const val APPEND = 1 shl 4
    const val TRUNCATE = 1 shl 5
    const val PATCH = 1 shl 6
    const val MARK = 1 shl 7
}