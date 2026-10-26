package com.snapshotpb.configuration.firmware

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * The [FirmwareHttp] of the apps: a Ktor client on the engine of the platform.
 *
 * Only this module holds the Ktor client core. The DI module of each platform gives the engine:
 * Android (`HttpURLConnection`) on Android, CIO on the desktop, Darwin on iOS, and Js in the
 * browser.
 *
 * The client follows redirects. It does not throw for an HTTP status. This class turns a status
 * that is not successful, and every failure of the engine, into [FirmwareFeedException.Http], so
 * a caller sees no Ktor type.
 *
 * The time limits are in [FirmwareHttpTimeouts]. The Js engine applies only the request limit, and
 * the Darwin engine does not apply the connect limit. On those engines, the request limit is the
 * only limit for a part that the engine does not watch.
 */
class KtorFirmwareHttp private constructor(
    private val client: HttpClient,
    private val timeouts: FirmwareHttpTimeouts,
) : FirmwareHttp {

    /** A client that makes its engine from [engineFactory], and closes it with the client. */
    constructor(engineFactory: HttpClientEngineFactory<*>) :
        this(HttpClient(engineFactory) { configure(FirmwareHttpTimeouts()) }, FirmwareHttpTimeouts())

    /** A client on [engine]. The caller owns the engine. */
    internal constructor(engine: HttpClientEngine, timeouts: FirmwareHttpTimeouts = FirmwareHttpTimeouts()) :
        this(HttpClient(engine) { configure(timeouts) }, timeouts)

    override suspend fun getText(url: String): String = get(url, configure = {}) { it.bodyAsText() }

    override suspend fun getBytes(
        url: String,
        maxBytes: Long,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): ByteArray = get(
        url = url,
        // The request limit of a text file is too short for an image on a slow link. The socket
        // limit stops a download that stops sending.
        configure = { timeout { requestTimeoutMillis = timeouts.imageRequestMillis } },
    ) { response ->
        val total = response.contentLength()
        if (total != null && total > maxBytes) {
            throw FirmwareFeedException.BadSize(url, expected = maxBytes, actual = total)
        }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        val chunks = mutableListOf<ByteArray>()
        var received = 0L
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            received += count
            if (received > maxBytes) {
                throw FirmwareFeedException.BadSize(url, expected = maxBytes, actual = received)
            }
            chunks += buffer.copyOf(count)
            onProgress(received, total)
        }
        // A body that ends before its Content-Length is a failure of the link, not a bad image. A
        // longer body can be correct: an engine that decompresses the body gives more bytes than
        // the compressed length in the header.
        if (total != null && received < total) {
            throw FirmwareFeedException.Http(url, status = null)
        }
        concatenate(chunks, received.toInt())
    }

    private suspend fun <T> get(
        url: String,
        configure: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> T,
    ): T = try {
        client.prepareGet(url, configure).execute { response ->
            if (!response.status.isSuccess()) {
                throw FirmwareFeedException.Http(url, response.status.value)
            }
            read(response)
        }
    } catch (error: CancellationException) {
        // A cancellation of the caller goes up. An engine can also stop a request that ran too
        // long with a cancellation while the caller is still active; that is a failure of the
        // request.
        currentCoroutineContext().ensureActive()
        throw FirmwareFeedException.Http(url, status = null, cause = error)
    } catch (error: FirmwareFeedException) {
        throw error
    } catch (error: Exception) {
        throw FirmwareFeedException.Http(url, status = null, cause = error)
    }

    private fun concatenate(chunks: List<ByteArray>, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024

        fun HttpClientConfig<*>.configure(timeouts: FirmwareHttpTimeouts) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = timeouts.connectMillis
                socketTimeoutMillis = timeouts.socketMillis
                requestTimeoutMillis = timeouts.textRequestMillis
            }
        }
    }
}

/**
 * The time limits of [KtorFirmwareHttp].
 *
 * @property connectMillis The limit for the connection to the site.
 * @property socketMillis The longest time with no data on the connection.
 * @property textRequestMillis The limit for all of one request of a JSON file.
 * @property imageRequestMillis The limit for all of one image download. 10 minutes is 900 KB at
 *   1.5 KB/s.
 */
internal data class FirmwareHttpTimeouts(
    val connectMillis: Long = 15_000,
    val socketMillis: Long = 30_000,
    val textRequestMillis: Long = 120_000,
    val imageRequestMillis: Long = 600_000,
)
