@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.share

import com.snapshotpb.configuration.common.share.ShareRequest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await

/** The result of one call of navigator.share. */
internal enum class WebShareResult { SHARED, ABORTED, FAILED }

/** What the app did with one request. */
internal enum class WebExportOutcome { SHARED, ABORTED, DOWNLOADED }

/** The browser calls of the export. Tests replace them. */
internal interface WebShareCalls {
    fun canShare(file: JsAny): Boolean
    suspend fun share(file: JsAny, title: String): WebShareResult
    fun download(file: JsAny, fileName: String)
}

/** The real browser calls. */
internal object BrowserShareCalls : WebShareCalls {
    override fun canShare(file: JsAny): Boolean = jsCanShare(file)

    override suspend fun share(file: JsAny, title: String): WebShareResult =
        when (jsShare(file, title).await<JsString>().toString()) {
            "shared" -> WebShareResult.SHARED
            "aborted" -> WebShareResult.ABORTED
            else -> WebShareResult.FAILED
        }

    override fun download(file: JsAny, fileName: String) {
        jsDownload(file, fileName)
    }
}

/** Gives a ShareRequest to the share sheet of the browser, or downloads it as a file. */
internal class WebShareHandler(private val calls: WebShareCalls = BrowserShareCalls) {
    suspend fun handle(request: ShareRequest): WebExportOutcome = when (request) {
        is ShareRequest.Text -> {
            val file = textFile(request.text, request.fileName, request.mimeType)
            if (!calls.canShare(file)) {
                calls.download(file, request.fileName)
                WebExportOutcome.DOWNLOADED
            } else {
                when (calls.share(file, request.title)) {
                    WebShareResult.SHARED -> WebExportOutcome.SHARED
                    WebShareResult.ABORTED -> WebExportOutcome.ABORTED
                    WebShareResult.FAILED -> {
                        calls.download(file, request.fileName)
                        WebExportOutcome.DOWNLOADED
                    }
                }
            }
        }
    }
}

/** Makes a JS File of [text] (UTF-8) with [fileName] and [mimeType]. */
internal fun textFile(text: String, fileName: String, mimeType: String): JsAny =
    js("new File([text], fileName, { type: mimeType })")

private fun jsCanShare(file: JsAny): Boolean = js(
    """(() => {
        try {
            return typeof navigator !== 'undefined' &&
                typeof navigator.share === 'function' &&
                typeof navigator.canShare === 'function' &&
                navigator.canShare({ files: [file] }) === true;
        } catch (e) {
            return false;
        }
    })()"""
)

private fun jsShare(file: JsAny, title: String): Promise<JsString> = js(
    """(() => {
        try {
            return Promise.resolve(navigator.share({ files: [file], title: title }))
                .then(
                    () => 'shared',
                    (e) => (e && e.name === 'AbortError') ? 'aborted' : 'failed'
                );
        } catch (e) {
            return Promise.resolve('failed');
        }
    })()"""
)

private fun jsDownload(file: JsAny, fileName: String): Unit = js(
    """{
        const url = URL.createObjectURL(file);
        const link = document.createElement('a');
        link.href = url;
        link.download = fileName;
        document.body.appendChild(link);
        try {
            link.click();
        } finally {
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
        }
    }"""
)
