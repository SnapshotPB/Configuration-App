package com.snapshotpb.configuration.firmware

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorFirmwareHttpTest {

    private val url = "https://snapshotpb.github.io/Firmware/snapshot_v2/latest.json"

    @Test
    fun getTextGivesTheBody() = runTest {
        val http = KtorFirmwareHttp(
            MockEngine { request ->
                assertEquals(url, request.url.toString())
                respond("""{"schema": 1}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        assertEquals("""{"schema": 1}""", http.getText(url))
    }

    @Test
    fun aStatusThatIsNotSuccessfulIsAnHttpFailure() = runTest {
        val http = KtorFirmwareHttp(MockEngine { respond("Not Found", HttpStatusCode.NotFound) })

        val error = assertFailsWith<FirmwareFeedException.Http> { http.getText(url) }

        assertEquals(404, error.status)
        assertTrue(error.isNotFound)
    }

    @Test
    fun aFailureOfTheEngineIsAnHttpFailureWithNoStatus() = runTest {
        val http = KtorFirmwareHttp(MockEngine { throw IllegalStateException("no network") })

        val error = assertFailsWith<FirmwareFeedException.Http> { http.getBytes(url, 10) { _, _ -> } }

        assertNull(error.status)
        assertEquals(url, error.url)
    }

    @Test
    fun getBytesGivesTheBodyAndTheProgress() = runTest {
        val body = ByteArray(20_000) { it.toByte() }
        val http = KtorFirmwareHttp(
            MockEngine {
                respond(ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentLength, body.size.toString()))
            },
        )
        val progress = mutableListOf<Pair<Long, Long?>>()

        val bytes = http.getBytes(url, maxBytes = body.size.toLong()) { received, total -> progress += received to total }

        assertContentEquals(body, bytes)
        assertEquals(body.size.toLong() to body.size.toLong(), progress.last())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
    }

    @Test
    fun aLongerBodyThanTheLimitStopsTheDownload() = runTest {
        val body = ByteArray(100)
        val withLength = KtorFirmwareHttp(
            MockEngine { respond(ByteReadChannel(body), headers = headersOf(HttpHeaders.ContentLength, "100")) },
        )
        val withNoLength = KtorFirmwareHttp(MockEngine { respond(ByteReadChannel(body)) })

        val first = assertFailsWith<FirmwareFeedException.BadSize> { withLength.getBytes(url, 99) { _, _ -> } }
        val second = assertFailsWith<FirmwareFeedException.BadSize> { withNoLength.getBytes(url, 99) { _, _ -> } }

        assertEquals(100, first.actual)
        assertEquals(99, second.expected)
    }

    @Test
    fun aBodyThatEndsBeforeItsLengthIsAnHttpFailure() = runTest {
        val http = KtorFirmwareHttp(
            MockEngine { respond(ByteReadChannel(ByteArray(60)), headers = headersOf(HttpHeaders.ContentLength, "100")) },
        )

        val error = assertFailsWith<FirmwareFeedException.Http> { http.getBytes(url, 100) { _, _ -> } }

        assertNull(error.status)
    }

    @Test
    fun aCancellationOfTheCallerStaysACancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = KtorFirmwareHttp(
            MockEngine {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        var failure: Throwable? = null
        val job = launch {
            try {
                http.getText(url)
            } catch (error: Throwable) {
                failure = error
                throw error
            }
        }

        started.await()
        job.cancelAndJoin()

        assertIs<CancellationException>(failure)
    }

    @Test
    fun aRequestThatRunsTooLongIsAnHttpFailureWithNoStatus() = runTest {
        val http = KtorFirmwareHttp(
            MockEngine {
                delay(10_000)
                respond("late")
            },
            FirmwareHttpTimeouts(textRequestMillis = 100),
        )

        val error = assertFailsWith<FirmwareFeedException.Http> { http.getText(url) }

        assertNull(error.status)
    }
}
