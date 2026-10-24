package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The board information in the 0xFFE0 service data of the board advertisement.
 *
 * The firmware builds the service data in `snapshot_bt_init()` (`app/src/subsystems/bluetooth/
 * bluetooth.c`) and sends it in the scan response:
 *
 * ```
 * [0xE0 0xFF]              16-bit service UUID, little-endian (not in the data Android gives)
 * [n][board type, n bytes] CONFIG_BOARD_TYPE_NAME, at most 15 bytes, for example "Cocker"
 * [4][b0 b1 b2 b3]         firmware version, see FirmwareVersion
 * ```
 *
 * [fwVersion] is the text to show. [firmwareVersion] is the same version as numbers, for a check
 * that compares versions. It is `null` when the data had a version in an older text form.
 */
@Serializable
data class AdvertisementData(
    val boardType: String,
    val fwVersion: String,
    val extraFields: List<String> = emptyList(),
    val firmwareVersion: FirmwareVersion? = null,
) {
    /**
     * [fwVersion] when it is text that a person can read, else `null`. The parser before #129 read
     * the 4 version bytes as text, and a device that it stored holds control characters until the
     * next scan of the board replaces them.
     */
    val readableFwVersion: String?
        get() = fwVersion.takeIf { version -> version.isNotEmpty() && version.none { it < ' ' } }

    companion object {
        /**
         * Parses the 0xFFE0 service data, with or without the two UUID bytes in front. Returns
         * `null` when the data is too short for the board type and the version.
         */
        fun parse(bytes: ByteArray): AdvertisementData? {
            var offset = 0
            if (bytes.size >= 2 && bytes[0] == 0xE0.toByte() && bytes[1] == 0xFF.toByte()) {
                offset = 2
            }

            // Parse Board Type
            if (offset >= bytes.size) return null
            val boardTypeLen = bytes[offset].toInt() and 0xFF
            offset++
            if (offset + boardTypeLen > bytes.size) return null
            val boardType = bytes.decodeToString(offset, offset + boardTypeLen)
            offset += boardTypeLen

            // Parse Firmware Version. The firmware sends 4 bytes, not text (see FirmwareVersion).
            // A field of another length is the text form of older firmware.
            if (offset >= bytes.size) return null
            val fwVersionLen = bytes[offset].toInt() and 0xFF
            offset++
            if (offset + fwVersionLen > bytes.size) return null
            val firmwareVersion = if (fwVersionLen == FIRMWARE_VERSION_SIZE) {
                FirmwareVersion.decode(bytes.copyOfRange(offset, offset + fwVersionLen))
            } else {
                null
            }
            val fwVersion = firmwareVersion?.toString() ?: bytes.decodeToString(offset, offset + fwVersionLen)
            offset += fwVersionLen

            // Forward support: parse any subsequent fields as length-prefixed hex strings
            val extraFields = mutableListOf<String>()
            while (offset < bytes.size) {
                val fieldLen = bytes[offset].toInt() and 0xFF
                offset++
                if (offset + fieldLen > bytes.size) {
                    break
                }
                val fieldBytes = bytes.copyOfRange(offset, offset + fieldLen)
                extraFields.add(fieldBytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
                offset += fieldLen
            }

            return AdvertisementData(boardType, fwVersion, extraFields, firmwareVersion)
        }
    }
}
