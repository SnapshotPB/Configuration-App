package com.snapshotpb.configuration.sync

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION
import com.snapshotpb.configuration.model.DEVICE_SYNC_SCHEMA_VERSION_KEY
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.PeerSyncOutcome
import com.snapshotpb.configuration.model.applyPeerDevice
import com.snapshotpb.configuration.store.DeviceStore
import com.toxicbakery.logging.Arbor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.kodein.di.DIAware
import org.kodein.di.instance

class WearDeviceSyncService : WearableListenerService(), DIAware {

    override val di by lazy { (applicationContext as DIAware).di }
    private val deviceStore: DeviceStore by instance()
    private val json: Json by instance()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        log.d { "onDataChanged received events from Phone" }
        for (event in dataEvents) {
            // Only a changed item is a signal. A deleted item is not: it is also what an uninstall
            // or a Play Services clean-up produces, and neither must forget a device here.
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            when {
                path.startsWith("${WearWatchSyncManager.DEVICES_PATH}/") -> {
                    val deviceIdStr = path.substringAfter("${WearWatchSyncManager.DEVICES_PATH}/")
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val deviceJson = dataMap.getString(WearWatchSyncManager.KEY_DEVICE_JSON) ?: continue
                    val lastModified = dataMap.getLong(WearWatchSyncManager.KEY_LAST_MODIFIED)
                    // A phone build from before the schema version sends no key, so version 0.
                    val schemaVersion = dataMap.getInt(DEVICE_SYNC_SCHEMA_VERSION_KEY, 0)
                    val photoAsset = dataMap.getAsset(WearWatchSyncManager.KEY_PHOTO_ASSET)

                    try {
                        val incomingDevice = json.decodeFromString<Device>(deviceJson)
                        updateLocalDevice(deviceIdStr, incomingDevice, lastModified, schemaVersion, photoAsset)
                    } catch (e: Exception) {
                        log.e(e) { "Failed to decode device JSON from Phone" }
                    }
                }
                path.startsWith("${WearWatchSyncManager.DELETED_PATH}/") -> {
                    removeLocalDevice(path.substringAfter("${WearWatchSyncManager.DELETED_PATH}/"))
                }
            }
        }
    }

    /**
     * Forgets a device the phone deleted, photo included. Play Services starts this service for
     * the marker even when the watch app is not running, so the watch cannot push its own copy
     * back at its next start. The store change makes [WearWatchSyncManager] clean the Data Layer.
     */
    private fun removeLocalDevice(deviceIdStr: String) {
        if (!awaitStoreLoaded("delete", deviceIdStr)) return
        val device = deviceStore.knownDevices.value.firstOrNull { it.id.toString() == deviceIdStr }
        if (device == null) {
            log.d { "Delete marker for $deviceIdStr from Phone: device already absent" }
            return
        }
        log.d { "Removing device $deviceIdStr on a delete marker from Phone" }
        // Each photo has a stamped name, so delete the file that photoPath names. Also delete the
        // fixed name that older builds wrote.
        ownedPhotoFileName(deviceIdStr, device.photoPath)?.let { deletePhotoFile(deviceIdStr, it) }
        deletePhotoFile(deviceIdStr, legacyPhotoFileName(deviceIdStr))
        deviceStore.update { current -> current.filter { it.id.toString() != deviceIdStr } }
    }

    /**
     * Play Services can start this service in a fresh process, where the store is still reading
     * devices.json. onDataChanged runs on a binder thread, so wait for the load here.
     */
    private fun awaitStoreLoaded(action: String, deviceIdStr: String): Boolean {
        val loaded = runBlocking { withTimeoutOrNull(STORE_LOAD_TIMEOUT_MILLIS) { deviceStore.awaitLoaded() } }
        if (loaded == null) {
            log.e { "Ignoring Phone $action for $deviceIdStr: the device store did not load in time" }
        }
        return loaded != null
    }

    private fun updateLocalDevice(
        deviceIdStr: String,
        incomingDevice: Device,
        incomingLastModified: Long,
        schemaVersion: Int,
        photoAsset: Asset?,
    ) {
        if (incomingDevice.id.toString() != deviceIdStr) {
            log.e { "Ignoring Phone sync: item $deviceIdStr holds device ${incomingDevice.id}" }
            return
        }
        if (schemaVersion < DEVICE_SYNC_SCHEMA_VERSION) {
            // Stop before the photo save. applyPeerDevice rejects the payload too.
            log.w { "Ignoring Phone sync for $deviceIdStr: schema $schemaVersion is older. Update the phone app." }
            return
        }
        if (!awaitStoreLoaded("sync", deviceIdStr)) return

        // The photo is saved before the store transform, because the transform must not do IO.
        // The file name has the phone stamp, so Coil shows a new photo and not its cached copy.
        // A second delivery of the same stamp finds its file in use and does not write it again.
        val fileName = photoFileName(deviceIdStr, incomingLastModified)
        val fileInUse = deviceStore.knownDevices.value
            .firstOrNull { it.id.toString() == deviceIdStr }
            ?.let { ownedPhotoFileName(deviceIdStr, it.photoPath) == fileName } == true
        val savedPhotoPath = photoAsset?.takeUnless { fileInUse }?.let { saveAssetToFile(deviceIdStr, it, fileName) }

        // The check and the merge run in one transform, so a board read or a second event that
        // lands at the same time cannot be lost or add the same id two times.
        var outcome: PeerSyncOutcome? = null
        var previousPhotoPath: String? = null
        deviceStore.update { current ->
            previousPhotoPath = current.firstOrNull { it.id == incomingDevice.id }?.photoPath
            val result = applyPeerDevice(
                current = current,
                incoming = incomingDevice,
                incomingLastModified = incomingLastModified,
                peerSchemaVersion = schemaVersion,
                localPhotoPath = savedPhotoPath,
            )
            outcome = result.outcome
            result.devices
        }
        when (outcome) {
            PeerSyncOutcome.ADDED -> log.d { "Added device $deviceIdStr from Phone" }
            PeerSyncOutcome.UPDATED -> log.d { "Updated device $deviceIdStr with config from Phone" }
            PeerSyncOutcome.IGNORED_NOT_NEWER ->
                log.d { "Ignoring Phone sync for $deviceIdStr: Phone TS ($incomingLastModified) is not newer" }
            PeerSyncOutcome.IGNORED_DELETED ->
                log.d { "Ignoring Phone sync for $deviceIdStr: the device was deleted" }
            PeerSyncOutcome.IGNORED_OLD_SCHEMA ->
                log.w { "Ignoring Phone sync for $deviceIdStr: schema $schemaVersion is older" }
            null -> Unit
        }
        if (savedPhotoPath != null) {
            cleanUpPhotoFiles(deviceIdStr, outcome, savedPhotoPath, previousPhotoPath)
        }
    }

    /**
     * Deletes the photo file that lost. An accepted payload replaces the old photo, so the file
     * that the old path named goes. A rejected payload keeps the old photo, so the new file goes.
     */
    private fun cleanUpPhotoFiles(
        deviceIdStr: String,
        outcome: PeerSyncOutcome?,
        savedPhotoPath: String,
        previousPhotoPath: String?,
    ) {
        val accepted = outcome == PeerSyncOutcome.ADDED || outcome == PeerSyncOutcome.UPDATED
        val staleName = if (accepted) {
            previousPhotoPath?.let { ownedPhotoFileName(deviceIdStr, it) }
        } else {
            ownedPhotoFileName(deviceIdStr, savedPhotoPath)
        }
        val keptName = if (accepted) ownedPhotoFileName(deviceIdStr, savedPhotoPath) else null
        if (staleName != null && staleName != keptName) deletePhotoFile(deviceIdStr, staleName)
    }

    private fun deletePhotoFile(deviceIdStr: String, fileName: String) {
        if (!deleteFile(fileName) && getFileStreamPath(fileName).exists()) {
            log.e { "Failed to delete photo file $fileName of device $deviceIdStr" }
        }
    }

    private fun saveAssetToFile(deviceIdStr: String, asset: Asset, fileName: String): String? {
        return try {
            log.d { "Saving photo asset to local file for device $deviceIdStr" }
            val tasks = Wearable.getDataClient(this).getFdForAsset(asset)
            // A bound, or a phone link that drops mid-transfer blocks this binder thread for good.
            val fdResult = Tasks.await(tasks, ASSET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val inputStream = fdResult.inputStream

            inputStream.use { input ->
                openFileOutput(fileName, MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
            // The store name, not an absolute path, as the phone stores it. The screens resolve
            // it with DeviceStore.photoUri. The files directory is the root of the store.
            fileName
        } catch (e: Exception) {
            log.e(e) { "Failed to save photo asset to file for device $deviceIdStr" }
            null
        }
    }

    /** One definition of the photo file name, so the write and the delete cannot disagree. */
    private fun photoFileName(deviceIdStr: String, stamp: Long): String =
        "${PHOTO_FILE_PREFIX}${deviceIdStr}_$stamp$PHOTO_FILE_SUFFIX"

    /** The fixed name that older builds wrote for every photo of a device. */
    private fun legacyPhotoFileName(deviceIdStr: String): String =
        "$PHOTO_FILE_PREFIX$deviceIdStr$PHOTO_FILE_SUFFIX"

    /**
     * The file name that [photoPath] points to, when that file is a watch photo of the device.
     * The result is `null` for any other file, so a delete never removes a file it does not own.
     */
    private fun ownedPhotoFileName(deviceIdStr: String, photoPath: String): String? {
        val name = photoPath.substringAfterLast('/')
        val stamped = name.startsWith("$PHOTO_FILE_PREFIX${deviceIdStr}_") && name.endsWith(PHOTO_FILE_SUFFIX)
        return name.takeIf { stamped || it == legacyPhotoFileName(deviceIdStr) }
    }

    companion object {
        private const val STORE_LOAD_TIMEOUT_MILLIS = 5_000L
        private const val ASSET_TIMEOUT_SECONDS = 30L
        private const val PHOTO_FILE_PREFIX = "wear_device_photo_"
        private const val PHOTO_FILE_SUFFIX = ".png"
        private val log = Arbor.tag("WearDeviceSyncService")
    }
}
