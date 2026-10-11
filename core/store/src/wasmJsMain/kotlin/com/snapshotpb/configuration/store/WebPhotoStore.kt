@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.store

import com.toxicbakery.logging.Arbor
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toInt8Array

private val log = Arbor.tag("WebPhotoStore")

/**
 * The device photos of the web app. IndexedDB keeps the bytes, so a photo stays after a reload.
 * `localStorage` is too small for images, and it holds text only.
 *
 * [StoreIOProvider] is synchronous, and IndexedDB is not. So [save] keeps the bytes in memory
 * until the IndexedDB write is complete, and [read] looks in memory first. The image loader reads
 * a photo with [read] (see `WebPhotoFetcher` in `core:common`).
 *
 * The browser does each write and each delete in the sequence of the calls: all requests go to one
 * object store, and each transaction starts when the database is open, in the sequence of the
 * calls. A delete after a save thus removes the saved photo.
 *
 * When the browser has no IndexedDB, or refuses it, the photos stay in memory for this page only.
 */
class WebPhotoStore internal constructor(databaseName: String) {

    private val database: Promise<JsAny>? = openPhotoDatabaseOrNull(databaseName)

    /** Photos with an IndexedDB write that is not complete, or that failed. */
    private val unsaved = mutableMapOf<String, ByteArray>()

    /** The last IndexedDB request. The requests complete in sequence, so this one completes last. */
    private var lastRequest: Promise<JsAny?>? = null

    /** True when the browser has IndexedDB. Without it, a photo does not stay after a reload. */
    val isPersistent: Boolean get() = database != null

    /** Keeps [bytes] as the photo [name], and starts the IndexedDB write. */
    fun save(name: String, bytes: ByteArray) {
        unsaved[name] = bytes
        val database = database ?: return
        lastRequest = putPhoto(database, name, bytes.toInt8Array()) { saved ->
            // A later save or a delete of the same name replaced or removed the entry. That call
            // controls the entry now.
            if (unsaved[name] !== bytes) return@putPhoto
            if (saved) {
                unsaved.remove(name)
            } else {
                log.w { "The IndexedDB write of $name failed. The photo stays in memory for this page only." }
            }
        }
    }

    /** Removes the photo [name]. */
    fun delete(name: String) {
        unsaved.remove(name)
        val database = database ?: return
        lastRequest = deletePhoto(database, name)
    }

    /** The photo [name] when it is in memory. Use [read] to get a photo that is only in IndexedDB. */
    internal fun inMemory(name: String): ByteArray? = unsaved[name]

    /** The photo [name], or null when there is no such photo. */
    suspend fun read(name: String): ByteArray? {
        unsaved[name]?.let { return it }
        val database = database ?: return null
        return try {
            getPhoto(database, name).await<Int8Array?>()?.toByteArray()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w(error) { "The IndexedDB read of $name failed" }
            null
        }
    }

    /** Waits until the IndexedDB requests that started before this call are complete. */
    internal suspend fun awaitRequests() {
        val request = lastRequest ?: return
        try {
            request.await<JsAny?>()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
    }

    companion object {
        /** The IndexedDB database of the web app. */
        private const val DATABASE_NAME = "snapshotpb"

        /** The one store of the page. Each [StoreIOProvider] of the page uses it. */
        val shared: WebPhotoStore by lazy { WebPhotoStore(DATABASE_NAME) }
    }
}

// ---- IndexedDB (Kotlin/Wasm) ----

/** Opens [name] with the object store `photos`. Null when the browser has no IndexedDB. */
private fun openPhotoDatabaseOrNull(name: String): Promise<JsAny>? = js(
    """(() => {
        if (typeof indexedDB === 'undefined' || !indexedDB) return null;
        let request;
        try {
            request = indexedDB.open(name, 1);
        } catch (e) {
            return null;
        }
        const opened = new Promise((resolve, reject) => {
            request.onupgradeneeded = () => {
                const db = request.result;
                if (!db.objectStoreNames.contains('photos')) db.createObjectStore('photos');
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
        opened.catch(() => {});
        return opened;
    })()"""
)

/** Writes [bytes] under [name], and calls [onDone] with true when the write is complete. */
private fun putPhoto(database: Promise<JsAny>, name: String, bytes: Int8Array, onDone: (Boolean) -> Unit): Promise<JsAny?> = js(
    """database.then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction('photos', 'readwrite');
        tx.objectStore('photos').put(bytes, name);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })).then(() => { onDone(true); return null; }, () => { onDone(false); return null; })"""
)

private fun deletePhoto(database: Promise<JsAny>, name: String): Promise<JsAny?> = js(
    """database.then((db) => new Promise((resolve, reject) => {
        const tx = db.transaction('photos', 'readwrite');
        tx.objectStore('photos').delete(name);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })).catch(() => null)"""
)

/** The bytes under [name], or null. */
private fun getPhoto(database: Promise<JsAny>, name: String): Promise<Int8Array?> = js(
    """database.then((db) => new Promise((resolve, reject) => {
        const request = db.transaction('photos', 'readonly').objectStore('photos').get(name);
        request.onsuccess = () => {
            const value = request.result;
            if (value === undefined || value === null) resolve(null);
            else if (value instanceof Int8Array) resolve(value);
            else resolve(new Int8Array(value instanceof ArrayBuffer ? value : value.buffer));
        };
        request.onerror = () => reject(request.error);
    }))"""
)
