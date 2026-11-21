package com.snapshotpb.configuration.share

import com.snapshotpb.configuration.common.share.ShareRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileSaverTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("desktop-file-saver-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun saveWritesTextAsUtf8() = runTest {
        val text = "µ … ü 板\nline 2\n"
        val target = File(tempDir, "log.txt")
        val saver = DesktopFileSaver(
            chooser = { _, _ -> target },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Save log",
            text = text,
        )

        val result = saver.save(request)

        assertEquals(target, result)
        assertContentEquals(text.encodeToByteArray(), target.readBytes())
    }

    @Test
    fun savePassesTitleAndSuggestedNameToChooser() = runTest {
        var receivedTitle: String? = null
        var receivedSuggestedName: String? = null
        val target = File(tempDir, "chosen.log")
        val saver = DesktopFileSaver(
            chooser = { title, suggestedName ->
                receivedTitle = title
                receivedSuggestedName = suggestedName
                target
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = ShareRequest.Text(
            fileName = "snapshot-ble.log",
            title = "Save Bluetooth Log",
            text = "line 1",
        )

        saver.save(request)

        assertEquals("Save Bluetooth Log", receivedTitle)
        assertEquals("snapshot-ble.log", receivedSuggestedName)
    }

    @Test
    fun saveReturnsChosenFile() = runTest {
        val target = File(tempDir, "output.txt")
        val saver = DesktopFileSaver(
            chooser = { _, _ -> target },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = ShareRequest.Text(
            fileName = "input.txt",
            title = "Save",
            text = "hello",
        )

        val result = saver.save(request)

        assertEquals(target, result)
    }

    @Test
    fun saveReturnsNullAndWritesNothingWhenUserCancels() = runTest {
        val saver = DesktopFileSaver(
            chooser = { _, _ -> null },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = ShareRequest.Text(
            fileName = "bluetooth.log",
            title = "Save log",
            text = "log content",
        )

        val result = saver.save(request)

        assertNull(result)
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun saveReplacesExistingFileContent() = runTest {
        val target = File(tempDir, "existing.log")
        target.writeText("old long content that must not remain", Charsets.UTF_8)
        val saver = DesktopFileSaver(
            chooser = { _, _ -> target },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = ShareRequest.Text(
            fileName = "existing.log",
            title = "Save log",
            text = "new",
        )

        val result = saver.save(request)

        assertEquals(target, result)
        assertContentEquals("new".encodeToByteArray(), target.readBytes())
    }

    @Test
    fun saveTargetOfReturnsNullWhenFileIsNullOrEmpty() {
        assertNull(saveTargetOf(null, null))
        assertNull(saveTargetOf("/a", null))
        assertNull(saveTargetOf("/a", ""))
    }

    @Test
    fun saveTargetOfBuildsFileFromDirectoryAndFileName() {
        assertEquals(File("/a", "b.txt"), saveTargetOf("/a", "b.txt"))
        assertEquals(File("b.txt"), saveTargetOf(null, "b.txt"))
    }
}
