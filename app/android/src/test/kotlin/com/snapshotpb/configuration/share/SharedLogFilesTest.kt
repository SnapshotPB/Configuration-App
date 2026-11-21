package com.snapshotpb.configuration.share

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedLogFilesTest {
    private lateinit var cacheDir: File

    @BeforeTest
    fun setUp() {
        cacheDir = createTempDirectory("shared-log-files-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun writeCreatesFileInSharedLogsDirectory() {
        val file = SharedLogFiles.write(cacheDir, "bluetooth.log", "line 1")

        assertEquals(File(File(cacheDir, SharedLogFiles.DIRECTORY), "bluetooth.log"), file)
        assertTrue(file.isFile)
    }

    @Test
    fun writeEncodesTextAsUtf8() {
        val text = "µ … ü 板\nline 2\n"

        val file = SharedLogFiles.write(cacheDir, "utf8.log", text)

        assertContentEquals(text.encodeToByteArray(), file.readBytes())
    }

    @Test
    fun writeDeletesOldFilesInSharedLogsOnly() {
        val outsideFile = File(cacheDir, "keep.txt")
        outsideFile.writeText("keep", Charsets.UTF_8)

        val first = SharedLogFiles.write(cacheDir, "first.log", "one")
        val second = SharedLogFiles.write(cacheDir, "second.log", "two")

        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertTrue(outsideFile.isFile)
        assertEquals("keep", outsideFile.readText(Charsets.UTF_8))
    }

    @Test
    fun writeStripsPathSegmentsFromFileName() {
        val escaped = SharedLogFiles.write(cacheDir, "../../escape.txt", "a")
        assertEquals(File(File(cacheDir, SharedLogFiles.DIRECTORY), "escape.txt"), escaped)

        val nested = SharedLogFiles.write(cacheDir, "a/b/c.txt", "b")
        assertEquals(File(File(cacheDir, SharedLogFiles.DIRECTORY), "c.txt"), nested)
    }

    @Test
    fun writeThrowsOnBlankFileName() {
        assertFailsWith<IllegalArgumentException> {
            SharedLogFiles.write(cacheDir, "", "text")
        }
        assertFailsWith<IllegalArgumentException> {
            SharedLogFiles.write(cacheDir, "   ", "text")
        }
    }

    @Test
    fun authorityAppendsSuffixToPackageName() {
        assertEquals(
            "com.snapshotpb.configuration.bluetoothlog",
            SharedLogFiles.authority("com.snapshotpb.configuration"),
        )
    }

    @Test
    fun pathsXmlAndManifestAgreeWithSharedLogFiles() {
        val pathsXml = File("src/main/res/xml/bluetooth_log_paths.xml").readText(Charsets.UTF_8)
        assertTrue(pathsXml.contains("""path="${SharedLogFiles.DIRECTORY}/""""))

        val manifestXml = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val expectedAuthority = SharedLogFiles.authority("\${applicationId}")
        assertTrue(manifestXml.contains("""android:authorities="$expectedAuthority""""))
    }
}
