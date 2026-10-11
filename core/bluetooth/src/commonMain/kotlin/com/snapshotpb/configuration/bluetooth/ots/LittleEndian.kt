package com.snapshotpb.configuration.bluetooth.ots

/**
 * OTS encodes every multi-byte integer little-endian on the wire. These helpers
 * keep the parsing code in [OtsClient] readable without dragging a kotlinx-io
 * dependency into commonMain just for byte slicing.
 */
internal fun ByteArray.readUInt32LE(offset: Int): Long {
    require(offset + 4 <= size) { "Not enough bytes for u32 at $offset (size=$size)" }
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}

internal fun ByteArray.readUInt48LE(offset: Int): Long {
    require(offset + 6 <= size) { "Not enough bytes for u48 at $offset (size=$size)" }
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24) or
        ((this[offset + 4].toLong() and 0xFF) shl 32) or
        ((this[offset + 5].toLong() and 0xFF) shl 40)
}

internal fun Long.toUInt32LE(): ByteArray =
    ByteArray(4) { i -> ((this ushr (i * 8)) and 0xFF).toByte() }

internal fun Long.toUInt48LE(): ByteArray =
    ByteArray(6) { i -> ((this ushr (i * 8)) and 0xFF).toByte() }
