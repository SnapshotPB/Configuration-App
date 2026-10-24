package com.snapshotpb.configuration.firmware

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
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
 */
class KtorFirmwareHttp(engine: HttpClientEngine) : FirmwareHttp {

    constructor(engineFactory: HttpClientEngineFactory<*>) : this(engineFactory.create())

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        }
    }

    override suspend fun getText(url: String): String = get(url) { it.bodyAsText() }

    override suspend fun getBytes(
        url: String,
        maxBytes: Long,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): ByteArray = get(url) { response ->
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
        concatenate(chunks, received.toInt())
    }

    private suspend fun <T> get(url: String, read: suspend (HttpResponse) -> T): T = try {
        client.prepareGet(url).execute { response ->
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
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 120_000L
    }
}
