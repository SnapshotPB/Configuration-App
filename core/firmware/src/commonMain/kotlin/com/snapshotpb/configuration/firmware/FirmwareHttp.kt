package com.snapshotpb.configuration.firmware

/**
 * The HTTP access of the firmware feed. [KtorFirmwareHttp] is the implementation of the apps. A
 * test gives a fake, so the tests of the feed need no network.
 *
 * Each function gives the body of a successful answer. For an answer that is not successful, and
 * for a request that gets no answer, it throws [FirmwareFeedException.Http].
 */
interface FirmwareHttp {

    /** Gets [url] and gives the body as UTF-8 text. */
    suspend fun getText(url: String): String

    /**
     * Gets [url] and gives the body as bytes.
     *
     * @param maxBytes The largest body to accept. A longer body stops the download with
     *   [FirmwareFeedException.BadSize].
     * @param onProgress Receives the bytes that arrived, and the length of the body, or `null` when
     *   the answer does not give the length.
     */
    suspend fun getBytes(
        url: String,
        maxBytes: Long,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): ByteArray
}
