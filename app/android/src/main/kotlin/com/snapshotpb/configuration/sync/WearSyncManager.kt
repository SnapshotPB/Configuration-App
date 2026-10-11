package com.snapshotpb.configuration.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION_KEY
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.syncedConfigEquals
import com.snapshotpb.configuration.store.DeviceStore
import com.toxicbakery.logging.Arbor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WearSyncManager(
    private val context: Context,
    private val deviceStore: DeviceStore,
    private val json: Json,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataClient = Wearable.getDataClient(context)

    // Bounded LRU cache: photoPath keys are never explicitly evicted, so cap the size
    // to avoid unbounded growth over the process lifetime. Wrapped in a synchronized map
    // for memory-visibility safety across the sync dispatcher.
    private val assetCache: MutableMap<String, Asset> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Asset>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Asset>): Boolean =
                size > MAX_ASSET_CACHE_SIZE
        },
    )

    // Read on the sync dispatcher (collectLatest) but written from the Play Services success
    // callback (main thread), so it must be thread-safe.
    private val lastSentDevices = ConcurrentHashMap<String, Device>()

    // startSync() is invoked from MainActivity.onCreate(), which re-runs on every Activity
    // recreation (rotation/dark-mode/font-scale/locale). Guard against launching duplicate
    // collectors on the long-lived manager scope.
    private val started = AtomicBoolean(false)

    fun startSync() {
        if (!started.compareAndSet(false, true)) {
            log.d { "WearSyncManager already started; ignoring duplicate startSync()" }
            return
        }
        log.d { "Starting WearSyncManager" }
        scope.launch {
            // The empty list before the disk load is not a store with no devices. Wait, or the
            // first loaded list would read as "every device was added" and a delete could hide
            // behind the load.
            deviceStore.awaitLoaded()
            // Ids in the previous emission; null until the first one. A device that leaves the
            // store is a delete, and one that enters it after a delete clears the tombstone.
            // PhoneDeviceSyncService does not add a device that the watch sends after a newer
            // delete, so such a device does not enter the store and its marker stays.
            var previousIds: Set<String>? = null
            deviceStore.knownDevices.collectLatest { devices ->
                val ids = devices.mapTo(mutableSetOf()) { it.id.toString() }
                previousIds?.let { previous ->
                    (previous - ids).forEach { removedId -> deleteDeviceFromWear(removedId) }
                    (ids - previous).forEach { addedId -> clearDeleteMarker(addedId) }
                }
                previousIds = ids
                devices.forEach { device ->
                    val lastSent = lastSentDevices[device.id.toString()]
                    // Send a device that was never sent, or one whose synced fields changed.
                    val needsSync = lastSent == null ||
                        lastSent.lastModified != device.lastModified ||
                        !lastSent.syncedConfigEquals(device)
                    if (needsSync) {
                        syncDeviceToWear(device)
                    }
                }
            }
        }
    }

    private fun syncDeviceToWear(device: Device) {
        log.d { "Syncing device to Wear: ${device.name} (id: ${device.id})" }
        val deviceIdStr = device.id.toString()
        val putDataMapReq = PutDataMapRequest.create("$DEVICES_PATH/$deviceIdStr")
        val dataMap = putDataMapReq.dataMap

        // Clear local photoPath for the synced JSON to prevent path mismatch on Wear OS
        val deviceJson = json.encodeToString(device.copy(photoPath = ""))
        dataMap.putString(KEY_DEVICE_JSON, deviceJson)
        dataMap.putLong(KEY_LAST_MODIFIED, device.lastModified)
        dataMap.putInt(DEVICE_SYNC_SCHEMA_VERSION_KEY, DEVICE_SYNC_SCHEMA_VERSION)

        // Process and scale photo. The stored photoPath is a store name, so resolve it against
        // the current root of the store first.
        if (device.photoPath.isNotEmpty()) {
            val photoUri = deviceStore.photoUri(device.photoPath)
            val cacheKey = photoCacheKey(photoUri)
            val cachedAsset = assetCache[cacheKey]
            if (cachedAsset != null) {
                log.d { "Using cached photo asset for ${device.name}" }
                dataMap.putAsset(KEY_PHOTO_ASSET, cachedAsset)
            } else {
                try {
                    val asset = createScaledPhotoAsset(photoUri)
                    if (asset != null) {
                        assetCache[cacheKey] = asset
                        dataMap.putAsset(KEY_PHOTO_ASSET, asset)
                        log.d { "Created new photo asset for ${device.name}" }
                    }
                } catch (e: Exception) {
                    log.e(e) { "Failed to scale and attach photo for ${device.name}" }
                }
            }
        }

        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq)
            .addOnSuccessListener {
                log.d { "Successfully put data item for device ${device.name}" }
                lastSentDevices[deviceIdStr] = device
            }
            .addOnFailureListener { e ->
                log.e(e) { "Failed to put data item for device ${device.name}" }
            }
    }

    /**
     * Tells the watch that the phone forgot a device.
     *
     * The device item itself is deleted so it does not stay in the Data Layer. That alone is not
     * the signal, because a TYPE_DELETED event has other causes than a user delete, and the watch
     * must not empty its store for one of them. The signal is a marker item at `/deleted/<id>`: the
     * watch removes the device when the marker arrives, which Play Services delivers to its listener
     * service even when the watch app is not running. So the watch no longer pushes its own copy
     * back at its next start. [clearDeleteMarker] removes the marker when the device comes back.
     */
    private fun deleteDeviceFromWear(deviceIdStr: String) {
        log.d { "Deleting device $deviceIdStr from Wear" }
        lastSentDevices.remove(deviceIdStr)
        dataClient.deleteDataItems(wearUri("$DEVICES_PATH/$deviceIdStr"))
            .addOnFailureListener { e -> log.e(e) { "Failed to delete the data item for device $deviceIdStr" } }
        val marker = PutDataMapRequest.create("$DELETED_PATH/$deviceIdStr")
        // A fresh timestamp keeps a repeated delete of the same id from being a byte-identical
        // item, which the Data Layer would not report as a change.
        marker.dataMap.putLong(KEY_DELETED_AT, Clock.System.now().toEpochMilliseconds())
        dataClient.putDataItem(marker.asPutDataRequest().setUrgent())
            .addOnSuccessListener { log.d { "Put delete marker for device $deviceIdStr" } }
            .addOnFailureListener { e -> log.e(e) { "Failed to put the delete marker for device $deviceIdStr" } }
    }

    /** Removes the delete marker for a device that is in the store again, so the marker cannot outlive it. */
    private fun clearDeleteMarker(deviceIdStr: String) {
        dataClient.deleteDataItems(wearUri("$DELETED_PATH/$deviceIdStr"))
            .addOnFailureListener { e -> log.e(e) { "Failed to clear the delete marker for device $deviceIdStr" } }
    }

    /**
     * Cache key for a device photo: the path plus the backing file's size and modification time.
     *
     * The path alone is NOT sufficient. Every device photo is stored under the same fixed name
     * (`device_photo_<deviceId>`), so retaking or re-picking a photo overwrites the file and leaves
     * the path unchanged — a path-keyed cache then served the previous bitmap to the watch forever.
     * Falls back to the bare path when the file cannot be stat'ed (e.g. a content:// URI), which is
     * no worse than the old behaviour.
     */
    private fun photoCacheKey(photoUri: String): String {
        val file = runCatching { File(Uri.parse(photoUri).path ?: photoUri) }.getOrNull()
        return if (file != null && file.exists()) {
            "$photoUri:${file.length()}:${file.lastModified()}"
        } else {
            photoUri
        }
    }

    private fun openPhotoStream(photoUri: String): InputStream? =
        try {
            context.contentResolver.openInputStream(Uri.parse(photoUri))
        } catch (e: Exception) {
            log.w(e) { "Could not open stream for URI: $photoUri, trying as file name" }
            // In case photoUri is a stored filename instead of complete URI
            context.openFileInput(photoUri.substringAfterLast("/"))
        }

    private fun createScaledPhotoAsset(photoUri: String): Asset? {
        // Read only the image size first. A full decode of a 12 MP photo needs about 48 MB, and
        // the watch gets a 250 px image, so decode at a reduced size with inSampleSize.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openPhotoStream(photoUri) ?: return null
        boundsStream.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, MAX_PHOTO_DIMENSION)
        }
        val decodeStream = openPhotoStream(photoUri) ?: return null
        val bitmap = decodeStream.use { stream -> BitmapFactory.decodeStream(stream, null, decodeOptions) }
            ?: return null

        val width = bitmap.width
        val height = bitmap.height
        val (newWidth, newHeight) = if (width > height) {
            val ratio = height.toFloat() / width
            MAX_PHOTO_DIMENSION to (MAX_PHOTO_DIMENSION * ratio).toInt()
        } else {
            val ratio = width.toFloat() / height
            (MAX_PHOTO_DIMENSION * ratio).toInt() to MAX_PHOTO_DIMENSION
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val bos = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val bytes = bos.toByteArray()

        // Clean up bitmap resources immediately to conserve memory
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        bitmap.recycle()

        return Asset.createFromBytes(bytes)
    }

    companion object {
        private const val MAX_ASSET_CACHE_SIZE = 32

        /** Length in pixels of the long side of the photo that the watch gets. */
        private const val MAX_PHOTO_DIMENSION = 250

        /** Item path prefix for a synced device; the listener services and the manifests use the same one. */
        const val DEVICES_PATH = "/devices"

        /** Item path prefix for a delete marker, see [deleteDeviceFromWear]. */
        const val DELETED_PATH = "/deleted"

        /** Data map key of the delete marker timestamp. */
        const val KEY_DELETED_AT = "deleted_at"

        /** Data map key of the device JSON in a device item. */
        const val KEY_DEVICE_JSON = "device_json"

        /** Data map key of the device `lastModified` in a device item. */
        const val KEY_LAST_MODIFIED = "last_modified"

        /** Data map key of the scaled photo in a device item. */
        const val KEY_PHOTO_ASSET = "photo_asset"

        private val log = Arbor.tag("WearSyncManager")

        /** A Data Layer URI with no node, so it matches the item at [path] from either side. */
        fun wearUri(path: String): Uri =
            Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

        /**
         * The largest power of two that keeps the long side of the decoded image at or above
         * [maxDimension], so the later scale step only makes the image smaller.
         */
        fun photoSampleSize(width: Int, height: Int, maxDimension: Int): Int {
            val longSide = maxOf(width, height)
            var sampleSize = 1
            while (longSide / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
