package com.snapshotpb.configuration.store

import com.snapshotpb.configuration.model.Device
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/**
 * Issue #136: a device photo must still show after the root of the store moves, as the iOS app
 * container does after an update or a reinstall. This test uses real files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePhotoMoveJvmTest {

    private val root: File = Files.createTempDirectory("photo-move").toFile()

    @AfterTest
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun aStoredPhotoOpensFromTheNewRootAfterTheDirectoryMoves() = runTest {
        val picked = File(root, "picked.jpg").apply { writeText("image-bytes") }
        val oldDir = File(root, "container-A")
        val firstStore = DeviceStore(
            storeIOProvider = StoreIOProviderJvm(baseDir = oldDir, legacyDir = null),
            json = appJson,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        testScheduler.advanceUntilIdle()
        val photoPath = firstStore.storePhotoFromUri("device_photo_x_1", picked.absolutePath)!!
        firstStore.update(listOf(device().copy(photoPath = photoPath)))
        testScheduler.advanceUntilIdle()

        val newDir = File(root, "container-B")
        assertTrue(oldDir.renameTo(newDir), "the test could not move the directory")
        val secondStore = DeviceStore(
            storeIOProvider = StoreIOProviderJvm(baseDir = newDir, legacyDir = null),
            json = appJson,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        testScheduler.advanceUntilIdle()

        val photoUri = secondStore.photoUri(secondStore.knownDevices.value.single().photoPath)
        val file = File(URI(photoUri))
        assertEquals(File(newDir, "device_photo_x_1").absolutePath, file.absolutePath)
        assertEquals("image-bytes", file.readText())
    }

    private fun device() = Device(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = "Marker",
        lastSeen = LocalDate(2026, 7, 26),
        knownProfiles = emptyList(),
    )
}
