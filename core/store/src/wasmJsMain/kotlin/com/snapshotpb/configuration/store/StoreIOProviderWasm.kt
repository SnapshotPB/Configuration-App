@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.store

import com.toxicbakery.logging.Arbor
import kotlin.io.encoding.Base64
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * Kotlin/Wasm [StoreIOProvider] on the `localStorage` of the browser, and on IndexedDB for the
 * device photos.
 *
 * There is no file system on wasm, so each store name has a `localStorage` entry. The content is
 * UTF-8 text, and `devices.json` is valid UTF-8. `localStorage` is synchronous, as the kotlinx-io
 * interface is, and its limit of about 5 MB is sufficient for the device list.
 *
 * A device photo (a store name that starts with [devicePhotoStoreName]) goes to [photos] in place
 * of `localStorage`, because a photo is binary and large. IndexedDB is asynchronous, so this
 * provider can only write and delete a photo. [exists], [sourceFor] and [move] refuse a photo
 * name, and the image loader reads a photo with [WebPhotoStore.read]. [pathFor] gives the same
 * key form for a photo, so the last segment of the photo path is the store name, as on the other
 * platforms.
 *
 * [sourceForUri] reads a `data:` URI with base64 content, which is the form that the web photo
 * picker gives.
 */
class StoreIOProviderWasm(
    private val photos: WebPhotoStore = WebPhotoStore.shared,
) : StoreIOProvider {

    override fun exists(storeName: String): Boolean {
        refusePhoto(storeName)
        return localStorageGetItem(key(storeName)) != null
    }

    override fun sinkFor(storeName: String): RawSink =
        if (isPhoto(storeName)) PhotoSink(storeName, photos) else LocalStorageSink(key(storeName))

    override fun sourceFor(storeName: String): RawSource {
        refusePhoto(storeName)
        val stored = localStorageGetItem(key(storeName)).orEmpty()
        return Buffer().apply { write(stored.encodeToByteArray()) }
    }

    override fun sourceForUri(uri: String): RawSource =
        Buffer().apply { write(decodeBase64DataUri(uri)) }

    override fun pathFor(storeName: String): String = key(storeName)

    override fun delete(storeName: String) {
        if (isPhoto(storeName)) photos.delete(storeName) else localStorageRemoveItem(key(storeName))
    }

    /**
     * One `setItem` replaces the whole entry at [to], so a reader never sees a part of it. Throws
     * when [from] is absent, so that the caller does not replace [to] with an empty entry.
     *
     * Also throws when the `setItem` fails (a full quota, or blocked storage), and keeps [from].
     * The store moves an unreadable `devices.json` aside with this call, so a silent failure
     * followed by the remove deleted the only copy of the data.
     */
    override fun move(from: String, to: String) {
        refusePhoto(from)
        refusePhoto(to)
        val content = localStorageGetItem(key(from))
            ?: throw IllegalStateException("No localStorage entry for $from")
        check(localStorageSetItem(key(to), content)) { "Cannot write the localStorage entry for $to" }
        localStorageRemoveItem(key(from))
    }

    private fun key(storeName: String): String = "$KEY_PREFIX$storeName"

    private fun isPhoto(storeName: String): Boolean = storeName.startsWith(PHOTO_PREFIX)

    /** A photo is in IndexedDB, which is asynchronous. Use [WebPhotoStore.read] to read it. */
    private fun refusePhoto(storeName: String) {
        if (isPhoto(storeName)) {
            throw UnsupportedOperationException("The photo $storeName is in IndexedDB. Use WebPhotoStore.read.")
        }
    }

    private companion object {
        private const val KEY_PREFIX = "snapshotpb/"
        private val PHOTO_PREFIX = devicePhotoStoreName("")
    }
}

/** The scheme of a `data:` URI, and the marker of its base64 content. */
private const val DATA_URI_SCHEME = "data:"
private const val BASE64_MARKER = ";base64,"

/**
 * The bytes of a `data:` URI with base64 content, such as `data:image/jpeg;base64,...`. Throws
 * [IllegalArgumentException] for any other URI: the web app has no other source of a photo.
 */
internal fun decodeBase64DataUri(uri: String): ByteArray {
    require(uri.startsWith(DATA_URI_SCHEME)) { "Only a data: URI can be read on the web" }
    val start = uri.indexOf(BASE64_MARKER)
    val comma = uri.indexOf(',')
    require(start >= 0 && start + BASE64_MARKER.length - 1 == comma) { "The data: URI has no base64 content" }
    return Base64.decode(uri, startIndex = comma + 1)
}

/**
 * A [RawSink] that keeps the written bytes and gives them to [photos] on [close]. A buffered sink
 * calls flush() and then close() when it closes, so only close() saves. Thus one photo gives one
 * IndexedDB write.
 */
private class PhotoSink(private val name: String, private val photos: WebPhotoStore) : RawSink {
    private val buffered = Buffer()
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        source.readTo(buffered, byteCount)
    }

    override fun flush() = Unit

    override fun close() {
        if (closed) return
        closed = true
        photos.save(name, buffered.readByteArray())
    }
}

/**
 * A [RawSink] that accumulates written bytes and flushes the full content to a `localStorage`
 * entry. Persisting the whole accumulated payload on every flush/close keeps it idempotent — the
 * buffered-sink teardown calls flush() then close(), and both must leave the same content in place.
 */
private class LocalStorageSink(private val key: String) : RawSink {
    // Accumulate into a Buffer and persist only on flush/close. Re-encoding and rewriting the whole
    // payload on every `write` made this O(n^2) in chunk count for no benefit — the buffered-sink
    // teardown always calls flush() then close(), so a chunked write still lands in full.
    private val buffered = Buffer()

    override fun write(source: Buffer, byteCount: Long) {
        source.readTo(buffered, byteCount)
    }

    override fun flush() = persist()

    override fun close() = persist()

    /**
     * Idempotent: flush() then close() must leave the same content in place. Throws when the
     * `setItem` fails, so that the caller does not move an old entry over the real one.
     */
    private fun persist() {
        check(localStorageSetItem(key, buffered.copy().readByteArray().decodeToString())) {
            "Cannot write the localStorage entry $key"
        }
    }
}

private val log = Arbor.tag("StoreIOProviderWasm")

private fun localStorageGetItem(key: String): String? =
    try {
        localStorageGetItemRaw(key)
    } catch (e: Throwable) {
        log.w(e) { "localStorage.getItem blocked/failed for $key" }
        null
    }

/** Returns `false` when the browser refused the write (a full quota, or blocked storage). */
private fun localStorageSetItem(key: String, value: String): Boolean {
    try {
        localStorageSetItemRaw(key, value)
    } catch (e: Throwable) {
        log.w(e) { "localStorage.setItem blocked/failed for $key" }
        return false
    }
    return true
}

private fun localStorageRemoveItem(key: String) {
    try {
        localStorageRemoveItemRaw(key)
    } catch (e: Throwable) {
        log.w(e) { "localStorage.removeItem blocked/failed for $key" }
    }
}

private fun localStorageGetItemRaw(key: String): String? = js("window.localStorage.getItem(key)")

private fun localStorageRemoveItemRaw(key: String): Unit = js("window.localStorage.removeItem(key)")

private fun localStorageSetItemRaw(key: String, value: String): Unit = js("window.localStorage.setItem(key, value)")