package com.snapshotpb.configuration.bluetooth.smp

/**
 * Reads the MCUboot image format of an update image file (`bootutil/image.h` in MCUboot).
 *
 * The file is the 32-byte image header, the body, an optional protected TLV area and the TLV area.
 * The TLV area holds the SHA-256 of the header and the body (`IMAGE_TLV_SHA256`). `image list`
 * reports this hash for a slot, so the app can tell whether a slot already holds the image.
 */
object McubootImage {
    private const val HEADER_SIZE = 32
    private const val IMAGE_MAGIC = 0x96f3b83dL
    private const val TLV_INFO_MAGIC = 0x6907
    private const val TLV_PROT_INFO_MAGIC = 0x6908
    private const val TLV_SHA256 = 0x10
    private const val TLV_ERASED_TYPE = 0xff
    private const val TLV_ERASED_LENGTH = 0xffff
    private const val TLV_INFO_SIZE = 4
    private const val TLV_HEADER_SIZE = 4
    private const val SHA256_SIZE = 32

    private const val MAGIC_OFFSET = 0
    private const val HEADER_SIZE_OFFSET = 8
    private const val IMAGE_SIZE_OFFSET = 12

    /**
     * The MCUboot image hash of [image], as `img_mgmt_read_info()` reads it from a slot: the
     * protected TLV area is skipped, and the TLV area must hold one SHA-256 TLV. Returns `null` when
     * [image] is not an MCUboot image, or when its TLVs do not hold exactly one SHA-256.
     */
    fun hashOf(image: ByteArray): ImageHash? {
        if (image.size < HEADER_SIZE || image.uInt32(MAGIC_OFFSET) != IMAGE_MAGIC) return null
        var offset = image.uInt16(HEADER_SIZE_OFFSET).toLong() + image.uInt32(IMAGE_SIZE_OFFSET)
        if (!image.has(offset, TLV_INFO_SIZE)) return null
        if (image.uInt16(offset.toInt()) == TLV_PROT_INFO_MAGIC) {
            // The total of the protected area includes its own info header.
            offset += image.uInt16(offset.toInt() + 2)
            if (!image.has(offset, TLV_INFO_SIZE)) return null
        }
        if (image.uInt16(offset.toInt()) != TLV_INFO_MAGIC) return null
        val end = minOf(offset + TLV_INFO_SIZE + image.uInt16(offset.toInt() + 2), image.size.toLong())
        var position = offset + TLV_INFO_SIZE
        var hash: ByteArray? = null
        while (position + TLV_HEADER_SIZE <= end) {
            val type = image.uInt16(position.toInt())
            val length = image.uInt16(position.toInt() + 2)
            if (type == TLV_ERASED_TYPE && length == TLV_ERASED_LENGTH) return null
            val data = position + TLV_HEADER_SIZE
            if (type == TLV_SHA256 && length == SHA256_SIZE) {
                if (hash != null || data + SHA256_SIZE > end) return null
                hash = image.copyOfRange(data.toInt(), data.toInt() + SHA256_SIZE)
            }
            position = data + length
        }
        return hash?.let(::ImageHash)
    }

    private fun ByteArray.has(offset: Long, length: Int): Boolean = offset >= 0 && offset + length <= size

    private fun ByteArray.uInt16(offset: Int): Int =
        (this[offset].toInt() and BYTE_MASK) or ((this[offset + 1].toInt() and BYTE_MASK) shl BYTE_BITS)

    private fun ByteArray.uInt32(offset: Int): Long =
        (uInt16(offset).toLong()) or (uInt16(offset + 2).toLong() shl (2 * BYTE_BITS))

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
}
