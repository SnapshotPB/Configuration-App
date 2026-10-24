package com.snapshotpb.configuration.firmware

/**
 * A [FirmwareHttp] that gives the fixture tree in `src/jvmTest/resources/pages` at the URLs of the
 * firmware site. A URL with no fixture file gives HTTP 404, as the site does.
 *
 * A test can replace the answer of one URL with [replies], and read the URLs that the feed asked
 * for in [requests].
 */
class FakeFirmwareSite(private val siteUrl: String = "$FIRMWARE_SITE_URL/") : FirmwareHttp {

    /** An answer that replaces the fixture file of a URL. */
    sealed interface Reply {
        class Text(val text: String) : Reply
        class Bytes(val bytes: ByteArray) : Reply
        class Status(val status: Int) : Reply
    }

    val replies = mutableMapOf<String, Reply>()

    val requests = mutableListOf<String>()

    override suspend fun getText(url: String): String = answer(url).decodeToString()

    override suspend fun getBytes(
        url: String,
        maxBytes: Long,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): ByteArray {
        val bytes = answer(url)
        if (bytes.size > maxBytes) {
            throw FirmwareFeedException.BadSize(url, expected = maxBytes, actual = bytes.size.toLong())
        }
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return bytes
    }

    private fun answer(url: String): ByteArray {
        requests += url
        return when (val reply = replies[url]) {
            is Reply.Text -> reply.text.encodeToByteArray()
            is Reply.Bytes -> reply.bytes
            is Reply.Status -> throw FirmwareFeedException.Http(url, reply.status)
            null -> fixture(url) ?: throw FirmwareFeedException.Http(url, HTTP_NOT_FOUND)
        }
    }

    private fun fixture(url: String): ByteArray? {
        if (!url.startsWith(siteUrl)) return null
        return javaClass.getResourceAsStream("/pages/" + url.removePrefix(siteUrl))?.use { it.readBytes() }
    }

    companion object {
        const val HTTP_NOT_FOUND = 404

        /** The text of a fixture file, by its path from the root of the site. */
        fun fixtureText(path: String): String =
            checkNotNull(FakeFirmwareSite::class.java.getResourceAsStream("/pages/$path")) {
                "The fixture pages/$path is not on the test classpath"
            }.use { it.readBytes().decodeToString() }
    }
}
