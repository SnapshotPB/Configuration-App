@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.store

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Tests of [WebPhotoStore] and of the photo path of [StoreIOProviderWasm], in a real browser with
 * IndexedDB. Each test uses a database of its own. A second [WebPhotoStore] on the same database
 * is a page after a reload: it has nothing in memory, so it reads IndexedDB.
 */
class WebPhotoStoreTest {

    private val databaseName = "snapshotpb-test-${Random.nextLong()}"

    @Test
    fun save_keepsThePhotoAfterAReload() = runTest {
        val store = WebPhotoStore(databaseName)
        assertTrue(store.isPersistent)

        store.save(PHOTO_NAME, PHOTO_BYTES)
        assertContentEquals(PHOTO_BYTES, store.inMemory(PHOTO_NAME), "the photo is in memory until the write ends")
        store.awaitRequests()

        assertNull(store.inMemory(PHOTO_NAME), "a complete write frees the memory")
        assertContentEquals(PHOTO_BYTES, WebPhotoStore(databaseName).read(PHOTO_NAME))
    }

    @Test
    fun delete_removesThePhotoAfterItsSave() = runTest {
        val store = WebPhotoStore(databaseName)

        store.save(PHOTO_NAME, PHOTO_BYTES)
        store.delete(PHOTO_NAME)
        store.awaitRequests()

        assertNull(store.read(PHOTO_NAME))
        assertNull(WebPhotoStore(databaseName).read(PHOTO_NAME))
    }

    @Test
    fun read_ofAnUnknownPhoto_givesNull() = runTest {
        assertNull(WebPhotoStore(databaseName).read("device_photo_unknown"))
    }

    @Test
    fun storePhotoFromUri_putsThePhotoInIndexedDbAndNotInLocalStorage() = runTest {
        val photos = WebPhotoStore(databaseName)
        val provider = StoreIOProviderWasm(photos)
        val deviceStore = DeviceStore(provider, appJson, StandardTestDispatcher(testScheduler))

        val path = deviceStore.storePhotoFromUri(PHOTO_NAME, PHOTO_DATA_URI)
        photos.awaitRequests()

        // The last segment is the store name, before and after the change to store names (#136).
        assertEquals(PHOTO_NAME, path?.substringAfterLast('/'))
        assertNull(localStorageItem("snapshotpb/$PHOTO_NAME"), "a photo must not go to localStorage")
        assertContentEquals(PHOTO_BYTES, WebPhotoStore(databaseName).read(PHOTO_NAME))

        provider.delete(PHOTO_NAME)
        photos.awaitRequests()
        assertNull(WebPhotoStore(databaseName).read(PHOTO_NAME))
    }

    @Test
    fun provider_keepsOtherEntriesInLocalStorage() {
        val provider = StoreIOProviderWasm(WebPhotoStore(databaseName))
        val name = "web-photo-store-test-${Random.nextLong()}.json"

        provider.sinkFor(name).buffered().use { it.write("[]".encodeToByteArray()) }

        assertEquals("[]", localStorageItem("snapshotpb/$name"))
        assertEquals("[]", provider.sourceFor(name).buffered().use { it.readByteArray() }.decodeToString())
        provider.delete(name)
        assertNull(localStorageItem("snapshotpb/$name"))
    }

    @Test
    fun provider_refusesASynchronousReadOfAPhoto() {
        val provider = StoreIOProviderWasm(WebPhotoStore(databaseName))

        assertFailsWith<UnsupportedOperationException> { provider.exists(PHOTO_NAME) }
        assertFailsWith<UnsupportedOperationException> { provider.sourceFor(PHOTO_NAME) }
        assertFailsWith<UnsupportedOperationException> { provider.move(PHOTO_NAME, "device_photo_test_2") }
    }

    @Test
    fun decodeBase64DataUri_givesTheBytes() {
        assertContentEquals(PHOTO_BYTES, decodeBase64DataUri(PHOTO_DATA_URI))
    }

    @Test
    fun decodeBase64DataUri_refusesAnyOtherUri() {
        assertFailsWith<IllegalArgumentException> { decodeBase64DataUri("content://media/1") }
        assertFailsWith<IllegalArgumentException> { decodeBase64DataUri("data:text/plain,SGk=") }
    }

    private companion object {
        const val PHOTO_NAME = "device_photo_test_1"

        /** The bytes 0, 1, 2, 127, -128 and -1: the base64 text is "AAECf4D/". */
        val PHOTO_BYTES = byteArrayOf(0, 1, 2, 127, -128, -1)
        const val PHOTO_DATA_URI = "data:image/jpeg;base64,AAECf4D/"
    }
}

private fun localStorageItem(key: String): String? = js("window.localStorage.getItem(key)")
