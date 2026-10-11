package com.snapshotpb.configuration.store

import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.EYE_THRESHOLD_DEFAULT
import com.snapshotpb.configuration.model.MarkerProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStoreTest {

    private val deviceId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val profileId = Uuid.parse("11111111-2222-3333-4444-555555555556")

    private fun device(
        id: Uuid = deviceId,
        name: String = "Marker",
        bootText: String = "SnapshotPB",
        profiles: List<MarkerProfile> = emptyList(),
        lastModified: Long = 0L,
    ) = Device(
        id = id,
        name = name,
        lastSeen = LocalDate(2026, 7, 26),
        lastModified = lastModified,
        knownProfiles = profiles,
        bootText = bootText,
    )

    private fun storeWith(
        io: FakeStoreIOProvider,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) = DeviceStore(storeIOProvider = io, json = appJson, dispatcher = dispatcher)

    // --- isLoaded: the signal that lets a screen tell "not yet" from "not there" ---

    @Test
    fun isLoaded_isFalseUntilTheDiskLoadCompletes() = runTest {
        val io = FakeStoreIOProvider(
            mapOf("devices.json" to appJson.encodeToString(listOf(device())))
        )
        // Standard (not Unconfined) so the init-launched load stays queued until we advance.
        val store = storeWith(io, StandardTestDispatcher(testScheduler))

        assertFalse(store.isLoaded.value, "load has not run yet")
        assertEquals(emptyList(), store.knownDevices.value, "StateFlow answers empty meanwhile")

        testScheduler.advanceUntilIdle()

        assertTrue(store.isLoaded.value)
        assertEquals(1, store.knownDevices.value.size)
    }

    @Test
    fun isLoaded_becomesTrueEvenWhenNothingIsStored() = runTest {
        val store = storeWith(FakeStoreIOProvider(), StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()
        assertTrue(store.isLoaded.value)
        assertEquals(emptyList(), store.knownDevices.value)
    }

    // --- eye-threshold migration (firmware a4b1c99 rescale) ---

    @Test
    fun load_resetsOutOfRangeEyeThresholdToTheDefault() = runTest {
        val legacy = device(
            profiles = listOf(MarkerProfile(id = profileId, name = "P0", eyeThreshold = 750)),
        )
        val io = FakeStoreIOProvider(
            mapOf("devices.json" to appJson.encodeToString(listOf(legacy)))
        )
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertEquals(
            EYE_THRESHOLD_DEFAULT,
            store.knownDevices.value.single().knownProfiles.single().eyeThreshold,
            "a legacy 0-1000 ADC value must reset, not clamp to 100",
        )
    }

    @Test
    fun update_normalizesEyeThresholdSoWatchSyncedValuesAreCoveredToo() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(
            listOf(device(profiles = listOf(MarkerProfile(id = profileId, name = "P0", eyeThreshold = 900))))
        )
        testScheduler.advanceUntilIdle()

        assertEquals(
            EYE_THRESHOLD_DEFAULT,
            store.knownDevices.value.single().knownProfiles.single().eyeThreshold,
        )
    }

    @Test
    fun load_leavesInRangeEyeThresholdAlone() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(profiles = listOf(MarkerProfile(id = profileId, name = "P0", eyeThreshold = 100)))))
        testScheduler.advanceUntilIdle()

        assertEquals(100, store.knownDevices.value.single().knownProfiles.single().eyeThreshold)
    }

    // --- stored profile names ---

    @Test
    fun load_keepsAStoredNineCharacterProfileName() = runTest {
        // Before the limit was 10, the app stored names of at most 9 characters. The store
        // loads such a name with no change.
        val stored = device(profiles = listOf(MarkerProfile(id = profileId, name = "Semi-Auto")))
        val io = FakeStoreIOProvider(
            mapOf("devices.json" to appJson.encodeToString(listOf(stored)))
        )
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertEquals("Semi-Auto", store.knownDevices.value.single().knownProfiles.single().name)
    }

    // --- persistence + merge bookkeeping ---

    @Test
    fun update_persistsToTheProvider() = runTest {
        val io = FakeStoreIOProvider()
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(name = "Written")))
        testScheduler.advanceUntilIdle()

        val written = io.textAt("devices.json")
        assertTrue(written != null && written.contains("Written"), "expected persisted JSON, got $written")
    }

    @Test
    fun update_stampsLastModifiedOnlyWhenConfigurationActuallyChanged() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(bootText = "First")))
        testScheduler.advanceUntilIdle()
        val afterInsert = store.knownDevices.value.single().lastModified
        assertTrue(afterInsert > 0L, "a new device gets a timestamp")

        // Re-writing identical configuration must not bump the stamp — the Wear sync uses it as a
        // change signal, and a bump on every no-op write would resync the watch continuously.
        store.update(listOf(device(bootText = "First", lastModified = afterInsert)))
        testScheduler.advanceUntilIdle()
        assertEquals(afterInsert, store.knownDevices.value.single().lastModified)
    }

    @Test
    fun update_stampsABoardStateOnlyChangeSoThatItReachesTheWearPeer() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(lastModified = 100L)))
        testScheduler.advanceUntilIdle()

        val baseline = BoardState(
            bootText = "SnapshotPB",
            activeProfileId = null,
            lockoutEnabled = false,
            knownProfiles = emptyList(),
        )
        store.update { current -> current.map { it.copy(boardState = baseline) } }
        testScheduler.advanceUntilIdle()

        assertTrue(store.knownDevices.value.single().lastModified > 100L)
    }

    @Test
    fun update_stampIncreasesEvenWhenTheOldStampIsAheadOfTheClock() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // A stamp from a peer whose clock is far ahead of this one.
        val future = Long.MAX_VALUE / 2
        store.update(listOf(device(name = "Peer", lastModified = future)))
        testScheduler.advanceUntilIdle()

        store.update { current -> current.map { it.copy(name = "Local") } }
        testScheduler.advanceUntilIdle()

        assertEquals(future + 1, store.knownDevices.value.single().lastModified)
    }

    @Test
    fun update_writesThroughATemporaryEntryAndLeavesNoTemporaryEntry() = runTest {
        val io = FakeStoreIOProvider()
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(name = "Written")))
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("devices.json"), io.content.keys)
    }

    @Test
    fun load_movesATruncatedFileAsideSoThatTheNextWriteCannotDestroyIt() = runTest {
        val full = appJson.encodeToString(listOf(device(name = "Saved")))
        val truncated = full.substring(0, full.length / 2)
        val io = FakeStoreIOProvider(mapOf("devices.json" to truncated))
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertTrue(store.isLoaded.value, "a failed decode must still finish the load")
        assertEquals(emptyList(), store.knownDevices.value)
        val asideKey = io.content.keys.single { it.startsWith("devices.json.corrupt-") }
        assertEquals(truncated, io.textAt(asideKey))

        // The next write creates a new devices.json and leaves the moved copy alone.
        store.update(listOf(device(id = Uuid.parse("99999999-2222-3333-4444-555555555555"), name = "New")))
        testScheduler.advanceUntilIdle()

        assertTrue(io.textAt("devices.json")?.contains("New") == true)
        assertEquals(truncated, io.textAt(asideKey))
    }

    @Test
    fun load_blocksWritesWhenTheUnreadableFileCannotBeMovedAside() = runTest {
        val fake = FakeStoreIOProvider(mapOf("devices.json" to "[{\"id\":"))
        val io = object : StoreIOProvider by fake {
            override fun move(from: String, to: String) {
                throw IllegalStateException("The store cannot rename $from")
            }
        }
        val store = DeviceStore(
            storeIOProvider = io,
            json = appJson,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(name = "New")))
        testScheduler.advanceUntilIdle()

        assertTrue(store.isLoaded.value)
        assertEquals("[{\"id\":", fake.textAt("devices.json"), "the only copy must stay as it was")
        assertNull(fake.textAt("devices.json.tmp"), "no write may start")
    }

    @Test
    fun update_transformOverloadSeesCurrentState() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device(name = "Before")))
        testScheduler.advanceUntilIdle()

        store.update { current -> current.map { it.copy(name = it.name + "/After") } }
        testScheduler.advanceUntilIdle()

        assertEquals("Before/After", store.knownDevices.value.single().name)
    }

    @Test
    fun loadMergesDiskUnderStateAppliedBeforeItFinished() = runTest {
        val onDisk = device(id = deviceId, name = "FromDisk")
        val otherId = Uuid.parse("99999999-2222-3333-4444-555555555555")
        val io = FakeStoreIOProvider(
            mapOf("devices.json" to appJson.encodeToString(listOf(onDisk)))
        )
        val store = storeWith(io, StandardTestDispatcher(testScheduler))

        // A user-driven mutation lands before the disk load has run.
        store.update(listOf(device(id = otherId, name = "AppliedDuringStartup")))
        testScheduler.advanceUntilIdle()

        val names = store.knownDevices.value.map { it.name }.toSet()
        assertEquals(setOf("AppliedDuringStartup", "FromDisk"), names, "neither side may clobber the other")
    }

    // --- stored files ---

    @Test
    fun deleteStoredFile_removesTheEntryAndToleratesAMiss() = runTest {
        val io = FakeStoreIOProvider(mapOf("device_photo_x" to "bytes"))
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.deleteStoredFile("device_photo_x")
        assertNull(io.textAt("device_photo_x"))

        // Deleting something that is already gone must not throw.
        store.deleteStoredFile("device_photo_x")
    }

    @Test
    fun devicePhotoStoreName_isStableForAGivenDevice() {
        assertEquals("device_photo_$deviceId", devicePhotoStoreName(deviceId.toString()))
    }

    @Test
    fun storePhotoFromUri_copiesContentAndReturnsTheStoreName() = runTest {
        val io = FakeStoreIOProvider()
        io.uris["content://photo"] = "image-bytes".encodeToByteArray()
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        val path = store.storePhotoFromUri("device_photo_x", "content://photo")

        assertEquals("device_photo_x", path, "the store keeps a name, not an absolute path")
        assertEquals("image-bytes", io.textAt("device_photo_x"))
        assertEquals("file:///fake/device_photo_x", store.photoUri("device_photo_x"))
    }

    @Test
    fun copyStoredPhoto_copiesTheStoredFileAndReturnsTheNewStoreName() = runTest {
        // The move of a device to its board id (#127) copies its photo by store name.
        val io = FakeStoreIOProvider(mapOf("device_photo_a_1" to "image-bytes"))
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        val name = store.copyStoredPhoto("device_photo_a_1", "device_photo_b_2")

        assertEquals("device_photo_b_2", name)
        assertEquals("image-bytes", io.textAt("device_photo_b_2"))
        assertEquals("image-bytes", io.textAt("device_photo_a_1"), "the copy keeps the old file")
    }

    // --- photo paths are store names (issue #136) ---

    @Test
    fun photoUri_resolvesAgainstTheNewRootAfterTheStoreMoves() = runTest {
        // iOS can give the app a new container path after an update or a reinstall. The files
        // move with the container, and the photo must still show.
        val before = FakeStoreIOProvider(root = "/containers/A")
        before.uris["content://photo"] = "image-bytes".encodeToByteArray()
        val firstStore = storeWith(before, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()
        val photoPath = firstStore.storePhotoFromUri("device_photo_x_1", "content://photo")!!
        firstStore.update(listOf(device().copy(photoPath = photoPath)))
        testScheduler.advanceUntilIdle()

        val after = FakeStoreIOProvider(root = "/containers/B", content = before.content)
        val secondStore = storeWith(after, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        val loaded = secondStore.knownDevices.value.single().photoPath
        assertEquals("device_photo_x_1", loaded)
        assertEquals("file:///containers/B/device_photo_x_1", secondStore.photoUri(loaded))
    }

    @Test
    fun load_changesALegacyAbsolutePhotoPathToItsStoreNameAndWritesItBack() = runTest {
        val legacy = device(lastModified = 500L).copy(photoPath = "file:///containers/A/device_photo_x_1")
        val io = FakeStoreIOProvider(
            initialContent = mapOf("devices.json" to appJson.encodeToString(listOf(legacy))),
            root = "/containers/B",
        )
        val store = storeWith(io, UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        val loaded = store.knownDevices.value.single()
        assertEquals("device_photo_x_1", loaded.photoPath)
        assertEquals(legacy.lastModified, loaded.lastModified, "the change is not an edit")
        assertEquals("file:///containers/B/device_photo_x_1", store.photoUri(loaded.photoPath))
        val written = io.textAt("devices.json").orEmpty()
        assertTrue("\"photoPath\":\"device_photo_x_1\"" in written, "expected the name on disk, got $written")
    }

    @Test
    fun update_changesAnAbsolutePhotoPathToItsStoreName() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        store.update(listOf(device().copy(photoPath = "file:///old/wear_device_photo_x_5.png")))
        testScheduler.advanceUntilIdle()

        assertEquals("wear_device_photo_x_5.png", store.knownDevices.value.single().photoPath)
    }

    @Test
    fun photoUri_leavesAnEmptyValueAndAPickerUriAsTheyAre() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertEquals("", store.photoUri(""))
        assertEquals("content://media/picker/0/42", store.photoUri("content://media/picker/0/42"))
        assertEquals("file:///cache/pick.jpg", store.photoUri("file:///cache/pick.jpg"))
    }

    @Test
    fun storePhotoFromUri_returnsNullRatherThanThrowingOnAnUnreadableSource() = runTest {
        val store = storeWith(FakeStoreIOProvider(), UnconfinedTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertNull(store.storePhotoFromUri("device_photo_x", "content://revoked"))
    }
}
