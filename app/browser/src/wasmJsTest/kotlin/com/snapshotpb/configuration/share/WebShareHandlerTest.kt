@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.share

import com.snapshotpb.configuration.common.share.ShareRequest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WebShareHandlerTest {
    @Test
    fun downloadsWhenBrowserCannotShare() = runTest {
        val calls = FakeWebShareCalls(canShareResult = false)
        val handler = WebShareHandler(calls)
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Share log",
            text = "line 1",
        )

        val outcome = handler.handle(request)

        assertEquals(WebExportOutcome.DOWNLOADED, outcome)
        assertEquals(1, calls.canShareFiles.size)
        assertEquals(0, calls.shareCalls.size)
        assertEquals(1, calls.downloadCalls.size)
        assertEquals("bluetooth.log", calls.downloadCalls.single().fileName)
    }

    @Test
    fun returnsSharedWhenNavigatorShareSucceeds() = runTest {
        val calls = FakeWebShareCalls(
            canShareResult = true,
            shareResult = WebShareResult.SHARED,
        )
        val handler = WebShareHandler(calls)
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Share Bluetooth Log",
            text = "line 1",
        )

        val outcome = handler.handle(request)

        assertEquals(WebExportOutcome.SHARED, outcome)
        assertEquals(1, calls.shareCalls.size)
        assertEquals("Share Bluetooth Log", calls.shareCalls.single().title)
        assertEquals(0, calls.downloadCalls.size)
    }

    @Test
    fun returnsAbortedWithoutDownloadWhenUserClosesShareSheet() = runTest {
        val calls = FakeWebShareCalls(
            canShareResult = true,
            shareResult = WebShareResult.ABORTED,
        )
        val handler = WebShareHandler(calls)
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Share log",
            text = "line 1",
        )

        val outcome = handler.handle(request)

        assertEquals(WebExportOutcome.ABORTED, outcome)
        assertEquals(1, calls.shareCalls.size)
        assertEquals(0, calls.downloadCalls.size)
    }

    @Test
    fun downloadsWhenNavigatorShareFails() = runTest {
        val calls = FakeWebShareCalls(
            canShareResult = true,
            shareResult = WebShareResult.FAILED,
        )
        val handler = WebShareHandler(calls)
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Share log",
            text = "line 1",
        )

        val outcome = handler.handle(request)

        assertEquals(WebExportOutcome.DOWNLOADED, outcome)
        assertEquals(1, calls.shareCalls.size)
        assertEquals(1, calls.downloadCalls.size)
        assertEquals("bluetooth.log", calls.downloadCalls.single().fileName)
    }

    @Test
    fun passesSameUtf8FileToCanShareShareAndDownload() = runTest {
        val text = "µ … ü 板\nline 2\n"
        val calls = FakeWebShareCalls(
            canShareResult = true,
            shareResult = WebShareResult.FAILED,
        )
        val handler = WebShareHandler(calls)
        val request = ShareRequest.Text(
            fileName = "snapshot-ble.log",
            title = "Share log",
            text = text,
            mimeType = "text/plain",
        )

        val outcome = handler.handle(request)

        assertEquals(WebExportOutcome.DOWNLOADED, outcome)
        val canShareFile = calls.canShareFiles.single()
        val shareFile = calls.shareCalls.single().file
        val downloadFile = calls.downloadCalls.single().file

        assertTrue(jsStrictEquals(canShareFile, shareFile))
        assertTrue(jsStrictEquals(canShareFile, downloadFile))
        assertEquals("snapshot-ble.log", jsFileName(canShareFile))
        assertEquals("text/plain", jsFileType(canShareFile))
        assertEquals(text.encodeToByteArray().size, jsFileSize(canShareFile))
    }

    private data class ShareCall(val file: JsAny, val title: String)
    private data class DownloadCall(val file: JsAny, val fileName: String)

    private class FakeWebShareCalls(
        private val canShareResult: Boolean,
        private val shareResult: WebShareResult = WebShareResult.SHARED,
    ) : WebShareCalls {
        val canShareFiles = mutableListOf<JsAny>()
        val shareCalls = mutableListOf<ShareCall>()
        val downloadCalls = mutableListOf<DownloadCall>()

        override fun canShare(file: JsAny): Boolean {
            canShareFiles += file
            return canShareResult
        }

        override suspend fun share(file: JsAny, title: String): WebShareResult {
            shareCalls += ShareCall(file, title)
            return shareResult
        }

        override fun download(file: JsAny, fileName: String) {
            downloadCalls += DownloadCall(file, fileName)
        }
    }
}

private fun jsFileName(file: JsAny): String = js("file.name")
private fun jsFileType(file: JsAny): String = js("file.type")
private fun jsFileSize(file: JsAny): Int = js("file.size")
private fun jsStrictEquals(a: JsAny, b: JsAny): Boolean = js("a === b")
