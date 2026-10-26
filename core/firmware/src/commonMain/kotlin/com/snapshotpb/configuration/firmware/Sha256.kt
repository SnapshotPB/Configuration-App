package com.snapshotpb.configuration.firmware

import org.kotlincrypto.hash.sha2.SHA256

private const val HEX_DIGITS = "0123456789abcdef"

/** The SHA-256 of [bytes] in lowercase hexadecimal, the form of the firmware site. */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = SHA256().digest(bytes)
    return buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}
