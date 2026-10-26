package com.snapshotpb.configuration.bluetooth.smp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An MCUboot image file in the form of `imgtool sign`: the 32-byte header, [body], an optional
 * protected TLV area, and the TLV area with the SHA-256 [hash] and the extra TLVs of [extraTlvs].
 */
internal fun mcubootImage(
    hash: ByteArray,
    body: ByteArray = ByteArray(1_000) { (it % 251).toByte() },
    protectedTlvs: Boolean = false,
    extraTlvs: List<Pair<Int, ByteArray>> = emptyList(),
    hashTlvs: Int = 1,
): ByteArray {
    fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    fun u32(value: Long) = u16((value and 0xFFFF).toInt()) + u16(((value ushr 16) and 0xFFFF).toInt())
    fun tlv(type: Int, data: ByteArray) = u16(type) + u16(data.size) + data
    val header = u32(0x96f3b83dL) + u32(0) + u16(32) + u16(0) + u32(body.size.toLong()) + u32(0) + ByteArray(8) + ByteArray(4)
    val protectedArea = if (protectedTlvs) {
        val entries = tlv(0x50, ByteArray(4))
        u16(0x6908) + u16(4 + entries.size) + entries
    } else {
        ByteArray(0)
    }
    val entries = extraTlvs.fold(ByteArray(0)) { all, (type, data) -> all + tlv(type, data) } +
        (1..hashTlvs).fold(ByteArray(0)) { all, _ -> all + tlv(0x10, hash) } +
        tlv(0x01, ByteArray(32))
    return header + body + protectedArea + u16(0x6907) + u16(4 + entries.size) + entries
}

class McubootImageTest {

    private val hash = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun theHashComesFromTheTlvArea() {
        assertEquals(ImageHash(hash), McubootImage.hashOf(mcubootImage(hash)))
    }

    @Test
    fun theProtectedAreaAndOtherTlvsAreSkipped() {
        val image = mcubootImage(hash, protectedTlvs = true, extraTlvs = listOf(0x20 to ByteArray(91)))

        assertEquals(ImageHash(hash), McubootImage.hashOf(image))
    }

    @Test
    fun aFileThatIsNotAnMcubootImageHasNoHash() {
        assertNull(McubootImage.hashOf(ByteArray(6_000) { (it % 251).toByte() }))
        assertNull(McubootImage.hashOf(ByteArray(8)))
    }

    @Test
    fun twoHashTlvsGiveNoHash() {
        // img_mgmt refuses such an image (TLV_MULTIPLE_HASHES_FOUND).
        assertNull(McubootImage.hashOf(mcubootImage(hash, hashTlvs = 2)))
    }

    @Test
    fun aCutFileGivesNoHash() {
        val image = mcubootImage(hash)

        assertNull(McubootImage.hashOf(image.copyOf(image.size - 40)))
    }
}
