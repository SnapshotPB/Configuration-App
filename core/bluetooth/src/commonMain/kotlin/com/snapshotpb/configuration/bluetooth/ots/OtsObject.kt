package com.snapshotpb.configuration.bluetooth.ots

/**
 * Snapshot of an OTS object's metadata, captured by reading the metadata
 * characteristics while the object is selected via OLCP.
 *
 * @param id 48-bit Object ID encoded as a [Long]; 0 means "Directory Listing"
 *   or unselected, anything in `0x0000_0000_0100..0xFFFF_FFFF_FFFF` is a real object.
 * @param sizeBytes current object size in bytes (the "useful" payload size).
 * @param allocatedBytes total bytes reserved on the server.
 * @param properties bitmask of [ObjectPropertyBit] flags advertising what the
 *   server permits — e.g. whether OACP Read is supported on this object.
 * @param typeUuid raw Object Type UUID bytes (2 or 16 bytes) as returned by the
 *   peripheral; not parsed because Zephyr can advertise vendor-specific types.
 */
data class OtsObject(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val allocatedBytes: Long,
    val properties: Int,
    val typeUuid: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtsObject) return false
        return id == other.id &&
            name == other.name &&
            sizeBytes == other.sizeBytes &&
            allocatedBytes == other.allocatedBytes &&
            properties == other.properties &&
            typeUuid.contentEquals(other.typeUuid)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + allocatedBytes.hashCode()
        result = 31 * result + properties
        result = 31 * result + typeUuid.contentHashCode()
        return result
    }
}