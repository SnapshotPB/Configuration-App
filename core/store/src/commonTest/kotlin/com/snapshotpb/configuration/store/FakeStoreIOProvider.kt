package com.snapshotpb.configuration.store

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * In-memory [StoreIOProvider] for tests: each "store" is a key in a map, so a [DeviceStore] can be
 * exercised without touching a filesystem or a browser.
 */
class FakeStoreIOProvider(
    initialContent: Map<String, String> = emptyMap(),
    /** The directory that [pathFor] puts before a store name. A test changes it to move the store. */
    private val root: String = "/fake",
    /** The entries. A second provider can share them to see the same store at a new root. */
    val content: MutableMap<String, ByteArray> =
        initialContent.mapValues { (_, text) -> text.encodeToByteArray() }.toMutableMap(),
) : StoreIOProvider {

    /** External URIs the fake can resolve for [DeviceStore.storePhotoFromUri]. */
    val uris: MutableMap<String, ByteArray> = mutableMapOf()

    fun textAt(storeName: String): String? = content[storeName]?.decodeToString()

    override fun exists(storeName: String): Boolean = content.containsKey(storeName)

    override fun sinkFor(storeName: String): RawSink = MapSink(storeName)

    override fun sourceFor(storeName: String): RawSource =
        Buffer().apply { write(content[storeName] ?: ByteArray(0)) }

    override fun sourceForUri(uri: String): RawSource =
        Buffer().apply { write(uris[uri] ?: error("No fake content registered for $uri")) }

    override fun pathFor(storeName: String): String = "$root/$storeName"

    override fun delete(storeName: String) {
        content.remove(storeName)
    }

    private inner class MapSink(private val storeName: String) : RawSink {
        private val buffered = Buffer()

        override fun write(source: Buffer, byteCount: Long) {
            source.readTo(buffered, byteCount)
        }

        override fun flush() = persist()

        override fun close() = persist()

        private fun persist() {
            content[storeName] = buffered.copy().readByteArray()
        }
    }
}
