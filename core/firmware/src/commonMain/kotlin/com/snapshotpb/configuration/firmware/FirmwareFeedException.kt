package com.snapshotpb.configuration.firmware

/**
 * A failure of the firmware feed. Each failure names the [url] that it applies to.
 *
 * The feed stops with one of these failures. It never gives an image whose size or hash is not
 * the value of the site.
 */
sealed class FirmwareFeedException(
    val url: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The site did not give the file.
     *
     * @property status The HTTP status of the answer, or `null` when no answer came (for example,
     *   no network, a timeout or a TLS failure).
     */
    class Http(url: String, val status: Int?, cause: Throwable? = null) : FirmwareFeedException(
        url = url,
        message = if (status != null) "HTTP $status for $url" else "No answer for $url",
        cause = cause,
    ) {
        /** True when the site answered that the file does not exist. */
        val isNotFound: Boolean
            get() = status == HTTP_NOT_FOUND
    }

    /** The file is not JSON, or it does not have the fields of the site format. */
    class BadJson(url: String, message: String, cause: Throwable? = null) :
        FirmwareFeedException(url, "Bad firmware site file $url: $message", cause)

    /** The file has a JSON format version that this app cannot read. */
    class UnsupportedSchema(url: String, val schema: Int) :
        FirmwareFeedException(url, "The firmware site file $url has the schema $schema")

    /** The image has a length that is not the length of the site. */
    class BadSize(url: String, val expected: Long, val actual: Long) :
        FirmwareFeedException(url, "The image $url has $actual bytes, not $expected")

    /** The SHA-256 of the image is not the SHA-256 of the site. */
    class BadHash(url: String, val expected: String, val actual: String) :
        FirmwareFeedException(url, "The SHA-256 of the image $url is $actual, not $expected")

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
