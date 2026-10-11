package com.snapshotpb.configuration.sync

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION_KEY
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.syncedConfigEquals
import com.snapshotpb.configuration.store.DeviceStore
import com.toxicbakery.logging.Arbor
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

class WearWatchSyncManager(
    private val context: Context,
    private val deviceStore: DeviceStore,
    private val json: Json,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataClient = Wearable.getDataClient(context)

    // Read on the sync dispatcher (collectLatest) but written from the Play Services success
    // callback (main thread), so it must be thread-safe.
    private val lastSentDevices = ConcurrentHashMap<String, Device>()

    // startSync() is invoked from MainActivity.onCreate(), which re-runs on every Activity
    // recreation (rotation/dark-mode/font-scale/locale). Guard against launching duplicate
    // collectors on the long-lived manager scope.
    private val started = AtomicBoolean(false)

    fun startSync() {
        if (!started.compareAndSet(false, true)) {
            log.d { "WearWatchSyncManager already started; ignoring duplicate startSync()" }
            return
        }
        log.d { "Starting WearWatchSyncManager" }
        scope.launch {
            // The empty list before the disk load is not a store with no devices. Wait, or the
            // first loaded list would read as "every device was added" and a delete could hide
            // behind the load.
            deviceStore.awaitLoaded()
            // Ids in the previous emission; null until the first one. A device that leaves the
            // store is a delete, and one that enters it after a delete clears the tombstone.
            var previousIds: Set<String>? = null
            deviceStore.knownDevices.collectLatest { devices ->
                val ids = devices.mapTo(mutableSetOf()) { it.id.toString() }
                previousIds?.let { previous ->
                    (previous - ids).forEach { removedId -> deleteDeviceFromPhone(removedId) }
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
                        syncDeviceToPhone(device)
                    }
                }
            }
        }
    }

    private fun syncDeviceToPhone(device: Device) {
        log.d { "Syncing device to Phone: ${device.name} (id: ${device.id})" }
        val deviceIdStr = device.id.toString()
        val putDataMapReq = PutDataMapRequest.create("$DEVICES_PATH/$deviceIdStr")
        val dataMap = putDataMapReq.dataMap

        // We clear photoPath since the watch doesn't manage phone-local photo paths
        val deviceJson = json.encodeToString(device.copy(photoPath = ""))
        dataMap.putString(KEY_DEVICE_JSON, deviceJson)
        dataMap.putLong(KEY_LAST_MODIFIED, device.lastModified)
        dataMap.putInt(DEVICE_SYNC_SCHEMA_VERSION_KEY, DEVICE_SYNC_SCHEMA_VERSION)

        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq)
            .addOnSuccessListener {
                log.d { "Successfully synced device $deviceIdStr to phone" }
                lastSentDevices[deviceIdStr] = device
            }
            .addOnFailureListener { e ->
                log.e(e) { "Failed to sync device $deviceIdStr to phone" }
            }
    }

    /**
     * Tells the phone that the watch forgot a device. Mirrors the phone side: the device item is
     * deleted, and a marker item at `/deleted/<id>` is the signal the phone acts on, because a
     * TYPE_DELETED event has other causes than a user delete. Today the watch forgets a device only
     * when the phone's marker arrives, so this is the echo that also cleans the watch's own item.
     */
    private fun deleteDeviceFromPhone(deviceIdStr: String) {
        log.d { "Deleting device $deviceIdStr from Phone" }
        lastSentDevices.remove(deviceIdStr)
        dataClient.deleteDataItems(wearUri("$DEVICES_PATH/$deviceIdStr"))
            .addOnFailureListener { e -> log.e(e) { "Failed to delete the data item for device $deviceIdStr" } }
        val marker = PutDataMapRequest.create("$DELETED_PATH/$deviceIdStr")
        marker.dataMap.putLong(KEY_DELETED_AT, Clock.System.now().toEpochMilliseconds())
        dataClient.putDataItem(marker.asPutDataRequest().setUrgent())
            .addOnFailureListener { e -> log.e(e) { "Failed to put the delete marker for device $deviceIdStr" } }
    }

    /** Removes the delete marker for a device that is in the store again, so the marker cannot outlive it. */
    private fun clearDeleteMarker(deviceIdStr: String) {
        dataClient.deleteDataItems(wearUri("$DELETED_PATH/$deviceIdStr"))
            .addOnFailureListener { e -> log.e(e) { "Failed to clear the delete marker for device $deviceIdStr" } }
    }

    /** A Data Layer URI with no node, so it matches the item at [path] from either side. */
    private fun wearUri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    companion object {
        /** Item path prefix for a synced device; the listener services and the manifests use the same one. */
        const val DEVICES_PATH = "/devices"

        /** Item path prefix for a delete marker, see [deleteDeviceFromPhone]. */
        const val DELETED_PATH = "/deleted"

        /** Data map key of the delete marker timestamp. */
        const val KEY_DELETED_AT = "deleted_at"

        /** Data map key of the device JSON in a device item. */
        const val KEY_DEVICE_JSON = "device_json"

        /** Data map key of the device `lastModified` in a device item. */
        const val KEY_LAST_MODIFIED = "last_modified"

        /** Data map key of the scaled photo in a device item from the phone. */
        const val KEY_PHOTO_ASSET = "photo_asset"

        private val log = Arbor.tag("WearWatchSyncManager")
    }
}
