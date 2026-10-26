package com.snapshotpb.configuration.bluetooth.smp

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor

/**
 * The CBOR codec of the SMP bodies.
 *
 * The board decodes with zcbor. When the firmware sets `CONFIG_ZCBOR_CANONICAL`, zcbor refuses an
 * indefinite-length map or list and an integer head that is longer than necessary
 * (`ZCBOR_ERR_INVALID_VALUE_ENCODING`). kotlinx-serialization-cbor writes indefinite-length maps by
 * default, so this codec sets [Cbor.configuration] `useDefiniteLengthEncoding`. The library always
 * writes the shortest integer head. A `ByteArray` field must have `@ByteString`, because the library
 * otherwise writes an array of integers, and zcbor reads `data`, `sha` and `hash` as byte strings.
 *
 * The board writes its responses with zcbor too. Without `CONFIG_ZCBOR_CANONICAL`, they have
 * indefinite-length maps and lists. The decoder reads both forms.
 *
 * The decoder ignores keys that a response class does not list, so a field from a later Zephyr
 * does not fail the decode. A property with a default value is not written when it has that value.
 */
internal object SmpCbor {
    private val cbor = Cbor {
        useDefiniteLengthEncoding = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** The CBOR of an empty map. A server sends at least this map in each response. */
    val EMPTY_MAP: ByteArray = byteArrayOf(0xA0.toByte())

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    /**
     * Decodes [bytes]. An empty body decodes as an empty map.
     *
     * @throws SmpMalformedFrame when [bytes] is not CBOR of the expected shape.
     */
    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        val body = if (bytes.isEmpty()) EMPTY_MAP else bytes
        return try {
            cbor.decodeFromByteArray(deserializer, body)
        } catch (error: IllegalArgumentException) {
            // SerializationException is a subclass of IllegalArgumentException.
            throw SmpMalformedFrame("The SMP body is not valid CBOR for ${deserializer.descriptor.serialName}", error)
        } catch (error: IndexOutOfBoundsException) {
            throw SmpMalformedFrame("The SMP body ends before its CBOR ends", error)
        }
    }

    /**
     * The number of bytes of a CBOR head with the argument [value] (RFC 8949 §3): 1 byte up to 23,
     * then 2, 3, 5 or 9 bytes.
     */
    fun headLength(value: Long): Int {
        require(value >= 0) { "A CBOR head argument cannot be negative: $value" }
        return when {
            value < ONE_BYTE_LIMIT -> 1
            value < UINT8_LIMIT -> 2
            value < UINT16_LIMIT -> 3
            value < UINT32_LIMIT -> 5
            else -> 9
        }
    }

    private const val ONE_BYTE_LIMIT = 24L
    private const val UINT8_LIMIT = 0x100L
    private const val UINT16_LIMIT = 0x1_0000L
    private const val UINT32_LIMIT = 0x1_0000_0000L
}
