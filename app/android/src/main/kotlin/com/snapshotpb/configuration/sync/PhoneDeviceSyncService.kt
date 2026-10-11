package com.snapshotpb.configuration.sync

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION_KEY
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.PeerSyncOutcome
import com.snapshotpb.configuration.model.applyPeerDevice
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.devicePhotoStoreName
import com.snapshotpb.configuration.store.storedDevicePhotoName
import com.toxicbakery.logging.Arbor
import java.util.concurrent.TimeUnit
import org.kodein.di.DIAware
import org.kodein.di.instance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

class PhoneDeviceSyncService : WearableListenerService(), DIAware {

    override val di by lazy { (applicationContext as DIAware).di }
    private val deviceStore: DeviceStore by instance()
    private val json: Json by instance()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        log.d { "onDataChanged received events from Wear OS" }
        for (event in dataEvents) {
            // Only a changed item is a signal. A deleted item is not: it is also what an uninstall
            // or a Play Services clean-up produces, and neither must forget a device here.
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            when {
                path.startsWith("${WearSyncManager.DEVICES_PATH}/") -> {
                    val deviceIdStr = path.substringAfter("${WearSyncManager.DEVICES_PATH}/")
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val deviceJson = dataMap.getString(WearSyncManager.KEY_DEVICE_JSON) ?: continue
                    val lastModified = dataMap.getLong(WearSyncManager.KEY_LAST_MODIFIED)
                    // A watch build from before the schema version sends no key, so version 0.
                    val schemaVersion = dataMap.getInt(DEVICE_SYNC_SCHEMA_VERSION_KEY, 0)

                    try {
                        val incomingDevice = json.decodeFromString<Device>(deviceJson)
                        updateLocalDevice(deviceIdStr, incomingDevice, lastModified, schemaVersion)
                    } catch (e: Exception) {
                        log.e(e) { "Failed to decode device JSON from Wear" }
                    }
                }
                path.startsWith("${WearSyncManager.DELETED_PATH}/") -> {
                    removeLocalDevice(path.substringAfter("${WearSyncManager.DELETED_PATH}/"))
                }
            }
        }
    }

    /**
     * Forgets a device the watch deleted, photo included, the same way the device browser does.
     * Nothing else runs here: the store change makes [WearSyncManager] clean the Data Layer.
     */
    private fun removeLocalDevice(deviceIdStr: String) {
        if (!awaitStoreLoaded("delete", deviceIdStr)) return
        val device = deviceStore.knownDevices.value.firstOrNull { it.id.toString() == deviceIdStr }
        if (device == null) {
            log.d { "Delete marker for $deviceIdStr from Wear OS: device already absent" }
            return
        }
        log.d { "Removing device $deviceIdStr on a delete marker from Wear OS" }
        // A photo has a stamped name since each new photo got its own file, so delete the file
        // that photoPath names. Also delete the fixed name that older builds wrote.
        storedDevicePhotoName(deviceIdStr, device.photoPath)?.let(deviceStore::deleteStoredFile)
        deviceStore.deleteStoredFile(devicePhotoStoreName(deviceIdStr))
        deviceStore.update { current -> current.filter { it.id.toString() != deviceIdStr } }
    }

    /**
     * Play Services can start this service in a fresh process, where the store is still reading
     * devices.json. onDataChanged runs on a binder thread, so wait for the load here.
     */
    private fun awaitStoreLoaded(action: String, deviceIdStr: String): Boolean {
        val loaded = runBlocking { withTimeoutOrNull(STORE_LOAD_TIMEOUT_MILLIS) { deviceStore.awaitLoaded() } }
        if (loaded == null) {
            log.e { "Ignoring Wear OS $action for $deviceIdStr: the device store did not load in time" }
        }
        return loaded != null
    }

    private fun updateLocalDevice(
        deviceIdStr: String,
        incomingDevice: Device,
        incomingLastModified: Long,
        schemaVersion: Int,
    ) {
        if (incomingDevice.id.toString() != deviceIdStr) {
            log.e { "Ignoring Wear OS sync: item $deviceIdStr holds device ${incomingDevice.id}" }
            return
        }
        if (!awaitStoreLoaded("sync", deviceIdStr)) return
        // Read before the store transform, because the transform must not do IO. The marker only
        // matters when the device is absent, and applyPeerDevice checks that in the same transform.
        val deleteMarkerAt = readDeleteMarkerAt(deviceIdStr)

        // The check and the merge run in one transform, so a board read or a second event that
        // lands at the same time cannot be lost or add the same id two times.
        var outcome: PeerSyncOutcome? = null
        deviceStore.update { current ->
            val result = applyPeerDevice(
                current = current,
                incoming = incomingDevice,
                incomingLastModified = incomingLastModified,
                peerSchemaVersion = schemaVersion,
                localDeleteMarkerAt = deleteMarkerAt,
            )
            outcome = result.outcome
            result.devices
        }
        when (outcome) {
            PeerSyncOutcome.ADDED -> log.d { "Added device $deviceIdStr from Wear OS" }
            PeerSyncOutcome.UPDATED -> log.d { "Updated device $deviceIdStr with configuration from Wear OS" }
            PeerSyncOutcome.IGNORED_NOT_NEWER ->
                log.d { "Ignoring Wear OS sync for $deviceIdStr: Wear OS TS ($incomingLastModified) is not newer" }
            PeerSyncOutcome.IGNORED_DELETED ->
                log.i { "Ignoring Wear OS sync for $deviceIdStr: the phone deleted it after that edit" }
            PeerSyncOutcome.IGNORED_OLD_SCHEMA ->
                log.w { "Ignoring Wear OS sync for $deviceIdStr: schema $schemaVersion is older. Update the Wear app." }
            null -> Unit
        }
    }

    /**
     * The latest delete marker time for [deviceIdStr] in the Data Layer, from either side, or
     * `null` when there is none or the read fails. The phone puts the marker when the user deletes
     * a device. A watch edit that crosses the delete must not bring the device back.
     */
    private fun readDeleteMarkerAt(deviceIdStr: String): Long? {
        val uri = WearSyncManager.wearUri("${WearSyncManager.DELETED_PATH}/$deviceIdStr")
        return try {
            val buffer = Tasks.await(
                Wearable.getDataClient(this).getDataItems(uri),
                MARKER_READ_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            try {
                buffer.maxOfOrNull { item ->
                    DataMapItem.fromDataItem(item).dataMap.getLong(WearSyncManager.KEY_DELETED_AT)
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            log.w(e) { "Cannot read the delete marker for $deviceIdStr" }
            null
        }
    }

    companion object {
        private const val STORE_LOAD_TIMEOUT_MILLIS = 5_000L
        private const val MARKER_READ_TIMEOUT_SECONDS = 5L
        private val log = Arbor.tag("PhoneDeviceSyncService")
    }
}
