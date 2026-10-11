package com.snapshotpb.configuration.store

import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.EYE_THRESHOLD_DEFAULT
import com.snapshotpb.configuration.model.EYE_THRESHOLD_MAX
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.syncedConfigEquals
import com.toxicbakery.logging.Arbor
import kotlin.time.Clock.System.now
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlinx.serialization.json.io.encodeToSink

class DeviceStore(
    private val storeIOProvider: StoreIOProvider,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher = storeIoDispatcher,
) {
    private val scope = CoroutineScope(
        dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            log.e(error) { "Store IO failed" }
        }
    )
    private val lock = Mutex()

    /**
     * True when an unreadable `devices.json` could not be moved aside. A write would then replace
     * the only copy of the old data, so no write occurs. Read and written only under [lock].
     */
    private var writesBlocked = false

    /**
     * Completed once the initial disk load has merged into memory. Persisting writes await this so a
     * mutation applied during startup can neither clobber nor be clobbered by the disk load.
     */
    private val initialLoad = CompletableDeferred<Unit>()

    private val _knownDevices: MutableStateFlow<List<Device>> = MutableStateFlow(emptyList())
    val knownDevices: StateFlow<List<Device>> = _knownDevices

    private val _isLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * False until the initial disk load has merged into [knownDevices]. Observers that need to
     * tell "this device does not exist" from "the store hasn't finished loading yet" must gate on
     * this — [knownDevices] is a [StateFlow] and so answers `emptyList()` immediately, which a
     * one-shot read cannot distinguish from a genuinely empty store.
     */
    val isLoaded: StateFlow<Boolean> = _isLoaded

    /**
     * Suspends until the initial disk load has merged into [knownDevices]. A caller that reads
     * [knownDevices] once, such as a Data Layer listener service that Play Services starts in a
     * fresh process, must wait here first: an empty list before the load is not an empty store,
     * and a write against it makes the load keep the in-memory record and drop the disk one.
     */
    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    init {
        readDevices()
    }

    /**
     * Atomically replaces the known-device set with [devices], preserving each device's
     * `lastModified` bookkeeping relative to the live state. The merge runs inside the
     * [MutableStateFlow.update] CAS block so a concurrent writer cannot be clobbered.
     */
    fun update(devices: List<Device>) {
        val nowTime = now().toEpochMilliseconds()
        _knownDevices.update { current -> mergeAgainst(devices, current, nowTime) }
        writeDevices()
    }

    /**
     * Atomically transforms the live known-device set. Prefer this over the [List] overload when the
     * new list is derived from current state, so the read-transform-write is a single atomic step and
     * cannot lose a concurrent update. [transform] must be side-effect free — it may run more than
     * once under CAS contention.
     */
    fun update(transform: (List<Device>) -> List<Device>) {
        val nowTime = now().toEpochMilliseconds()
        _knownDevices.update { current -> mergeAgainst(transform(current), current, nowTime) }
        writeDevices()
    }

    private fun mergeAgainst(
        devices: List<Device>,
        current: List<Device>,
        nowTime: Long,
    ): List<Device> = devices.map { rawNewDevice ->
        // Normalize the legacy eye-threshold scale on every store write (not just the disk load),
        // so devices arriving over the Wear Data Layer are covered too. The photo path gets the
        // same treatment, so an absolute path can never go back into the store.
        val newDevice = rawNewDevice.normalizedEyeThreshold().normalizedPhotoPath()
        val oldDevice = current.firstOrNull { it.id == newDevice.id }
        if (oldDevice == null) {
            if (newDevice.lastModified == 0L) {
                newDevice.copy(lastModified = nowTime)
            } else {
                newDevice
            }
        } else {
            // One list of synced fields for the store and the Wear sync. It includes boardState, so
            // a board read or a board write that changes only the baseline also gets a new stamp,
            // and the Wear sync sends the new baseline to the peer.
            val configChanged = !oldDevice.syncedConfigEquals(newDevice)

            if (configChanged) {
                if (newDevice.lastModified > oldDevice.lastModified) {
                    newDevice
                } else {
                    // The stamp must always increase. A clock that is behind the clock that made
                    // the old stamp would otherwise give a lower value, and the peer would ignore
                    // the edit.
                    newDevice.copy(lastModified = maxOf(nowTime, oldDevice.lastModified + 1))
                }
            } else {
                if (newDevice.lastModified > oldDevice.lastModified) {
                    newDevice
                } else {
                    newDevice.copy(lastModified = oldDevice.lastModified)
                }
            }
        }
    }

    /**
     * Copies the photo at [sourceUri] into the store under [storeName], off the caller's thread on
     * the store's IO dispatcher. Returns [storeName], the value for [Device.photoPath], or `null` if
     * the copy failed (logged, never thrown) so a revoked/deleted source URI cannot crash the UI.
     *
     * The result is a store name and not an absolute path. The root of the store can move: iOS can
     * give the app a new container path after an update or a reinstall. Use [photoUri] to get the
     * URI that an image loader opens.
     */
    suspend fun storePhotoFromUri(storeName: String, sourceUri: String): String? =
        withContext(dispatcher) {
            runCatching {
                storeIOProvider.sourceForUri(sourceUri)
                    .buffered()
                    .use { source ->
                        storeIOProvider.sinkFor(storeName)
                            .buffered()
                            .use { sink ->
                                // Streamed, not `sink.write(source.readByteArray())`: the old form
                                // held the entire image in memory, which is fine for a camera JPEG
                                // and not for an arbitrary gallery pick.
                                source.transferTo(sink)
                            }
                    }
                storeName
            }.onFailure { error ->
                log.e(error) { "Failed to store photo from $sourceUri" }
            }.getOrNull()
        }

    /**
     * Copies the stored photo [fromStoreName] to [toStoreName], off the caller's thread on the
     * store's IO dispatcher. Returns [toStoreName], the value for [Device.photoPath], or `null` if
     * the copy failed (logged, never thrown). The web keeps a photo in IndexedDB, which this
     * provider cannot read, so the copy fails there and the caller keeps the old photo.
     */
    suspend fun copyStoredPhoto(fromStoreName: String, toStoreName: String): String? =
        withContext(dispatcher) {
            runCatching {
                storeIOProvider.sourceFor(fromStoreName)
                    .buffered()
                    .use { source ->
                        storeIOProvider.sinkFor(toStoreName)
                            .buffered()
                            .use { sink -> source.transferTo(sink) }
                    }
                toStoreName
            }.onFailure { error ->
                log.e(error) { "Failed to copy photo $fromStoreName to $toStoreName" }
            }.getOrNull()
        }

    fun pathFor(storeName: String): String = storeIOProvider.pathFor(storeName)

    /**
     * The URI that an image loader opens for [photoPath], a value of [Device.photoPath]. A store
     * name resolves against the current root of the store, so the photo still loads after the
     * root moves. An empty value stays empty, and a value that is not a store name, such as a
     * `content://` URI from the photo picker, stays the same.
     */
    fun photoUri(photoPath: String): String = resolveDevicePhotoUri(photoPath, storeIOProvider::pathFor)

    /** Removes a stored file (currently only device photos). Best-effort; never throws. */
    fun deleteStoredFile(storeName: String) {
        runCatching { storeIOProvider.delete(storeName) }
            .onFailure { error -> log.d { "Deleting $storeName failed: ${error.message}" } }
    }

    private fun readDevices() = scope.launch {
        try {
            if (storeIOProvider.exists(DEVICES_PATH_NAME)) {
                log.d { "Reading devices." }
                lock.withLock {
                    val loaded = try {
                        storeIOProvider.sourceFor(DEVICES_PATH_NAME)
                            .buffered()
                            .use { source -> json.decodeFromSource<List<Device>>(source) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        moveUnreadableFileAside(error)
                        null
                    }
                    if (loaded != null) {
                        // Merge disk content into any state applied before the load finished:
                        // in-memory (user-driven) entries win; the disk supplies ids not yet
                        // present in memory. Normalize the legacy eye-threshold scale immediately
                        // on load so the value is never surfaced un-normalized before the first
                        // store write.
                        // An older build stored an absolute photo path. The load keeps only
                        // the file name, and a write then stores the new form one time.
                        _knownDevices.update { current ->
                            val currentIds = current.mapTo(mutableSetOf()) { it.id }
                            current + loaded.filter { it.id !in currentIds }
                                .map { it.normalizedEyeThreshold().normalizedPhotoPath() }
                        }
                        log.d { "Loaded ${loaded.size} devices." }
                        if (loaded.any { it.photoPath != photoStoreNameOf(it.photoPath) }) {
                            log.i { "The stored photo paths change to store names." }
                            writeDevices()
                        }
                    }
                }
            }
        } finally {
            initialLoad.complete(Unit)
            _isLoaded.value = true
        }
    }

    private fun writeDevices() = scope.launch {
        // Never persist before the initial disk load has merged, or a startup write would clobber
        // devices not yet read back from disk.
        initialLoad.await()
        lock.withLock {
            if (writesBlocked) {
                log.e { "Not writing devices: the unreadable $DEVICES_PATH_NAME could not be moved aside." }
                return@withLock
            }
            log.d { "Writing devices." }
            // Write the full list to a temporary entry, then move it over the real one. A process
            // stop during the write then leaves the old file whole, not a truncated one.
            storeIOProvider.sinkFor(DEVICES_TEMP_NAME)
                .buffered()
                .use { sink ->
                    json.encodeToSink(_knownDevices.value, sink)
                }
            storeIOProvider.move(DEVICES_TEMP_NAME, DEVICES_PATH_NAME)
        }
    }

    /**
     * Moves a `devices.json` that cannot be decoded to `devices.json.corrupt-<time>`. The store then
     * starts with no devices, and the next write cannot destroy the only copy of the old data. If
     * the move fails, writes stop for this process, for the same reason. Call only under [lock].
     */
    private fun moveUnreadableFileAside(error: Exception) {
        val asideName = "$DEVICES_PATH_NAME$CORRUPT_SUFFIX${now().toEpochMilliseconds()}"
        log.e(error) { "Cannot decode $DEVICES_PATH_NAME. Moving it to $asideName." }
        try {
            storeIOProvider.move(DEVICES_PATH_NAME, asideName)
        } catch (moveError: CancellationException) {
            throw moveError
        } catch (moveError: Exception) {
            writesBlocked = true
            log.e(moveError) { "Cannot move $DEVICES_PATH_NAME aside. Device writes stop for this session." }
        }
    }

    companion object {
        private const val DEVICES_PATH_NAME = "devices.json"
        private const val DEVICES_TEMP_NAME = "$DEVICES_PATH_NAME.tmp"
        private const val CORRUPT_SUFFIX = ".corrupt-"

        private val log = Arbor.tag("DeviceStore")
    }
}

/**
 * Store name for a device's badge photo. One definition so the write site, the Wear sync and the
 * delete path cannot disagree about where a photo lives.
 */
fun devicePhotoStoreName(deviceId: String): String = "device_photo_$deviceId"

/**
 * The store name of the photo file that [photoPath] points to, when that file is a photo of the
 * device [deviceId]. The result is `null` for an empty path or for a file that the app did not
 * write for this device, so a caller never deletes a file that it does not own.
 *
 * The name without a stamp is the form that older builds wrote, and it is accepted too.
 */
fun storedDevicePhotoName(deviceId: String, photoPath: String): String? {
    val name = photoPath.substringAfterLast('/')
    val base = devicePhotoStoreName(deviceId)
    return name.takeIf { it == base || it.startsWith("${base}_") }
}

/** The URI scheme of a local file. */
private const val FILE_URI_SCHEME = "file://"

/**
 * The store name that [photoPath] holds. Older builds stored an absolute `file://` path to the
 * photo, and the root of that path can be out of date. Such a path gives the file name after the
 * last `/`. Each photo file that the app wrote is at the root of the store, so that name is the
 * store name. Any other value stays the same.
 */
internal fun photoStoreNameOf(photoPath: String): String =
    if (photoPath.startsWith(FILE_URI_SCHEME)) photoPath.substringAfterLast('/') else photoPath

/**
 * The URI that an image loader opens for [photoPath]. [pathFor] gives the absolute path of a store
 * name at the current root of the store. See [DeviceStore.photoUri].
 *
 * Only a store name resolves: a value with no `/` and no `:`. The photo picker gives a URI with a
 * scheme, and that URI must not become a path in the store, so it stays the same. This function
 * does not change an absolute path of an older build: [DeviceStore] replaces each one with its
 * store name when it reads or writes a device, so a stored device never has one here.
 */
internal fun resolveDevicePhotoUri(photoPath: String, pathFor: (String) -> String): String {
    val isStoreName = photoPath.isNotEmpty() && '/' !in photoPath && ':' !in photoPath
    return if (isStoreName) "$FILE_URI_SCHEME${pathFor(photoPath)}" else photoPath
}

/** Replaces an absolute [Device.photoPath] of an older build with its store name. */
private fun Device.normalizedPhotoPath(): Device {
    val name = photoStoreNameOf(photoPath)
    return if (name == photoPath) this else copy(photoPath = name)
}

/** Applies [MarkerProfile.normalizedEyeThreshold] to the local profiles and to the cached board state. */
private fun Device.normalizedEyeThreshold(): Device = copy(
    knownProfiles = knownProfiles.map { it.normalizedEyeThreshold() },
    boardState = boardState?.let { bs ->
        bs.copy(knownProfiles = bs.knownProfiles.map { it.normalizedEyeThreshold() })
    },
)

/**
 * Resets an out-of-range [MarkerProfile.eyeThreshold] to the app default. Firmware `a4b1c99`
 * redefined the field from a raw 0–1000 ADC reading to a 0–100 % of ADC full scale; the old scale
 * does not map linearly to the new one, so a stored value above [EYE_THRESHOLD_MAX] is *reset* to
 * [EYE_THRESHOLD_DEFAULT] (not clamped to 100, which would wrongly max out eye sensitivity). The
 * board re-syncs the true percent on reconnect. Idempotent — no schema-version marker needed.
 */
private fun MarkerProfile.normalizedEyeThreshold(): MarkerProfile =
    if (eyeThreshold > EYE_THRESHOLD_MAX) copy(eyeThreshold = EYE_THRESHOLD_DEFAULT) else this
