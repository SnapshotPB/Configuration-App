package com.snapshotpb.configuration.store

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString

class StoreIOProviderJvmTest {

    private val root: File = Files.createTempDirectory("store-io-jvm").toFile()

    @AfterTest
    fun deleteRoot() {
        root.deleteRecursively()
    }

    private fun StoreIOProvider.write(storeName: String, text: String) {
        sinkFor(storeName).buffered().use { it.writeString(text) }
    }

    private fun StoreIOProvider.read(storeName: String): String =
        sourceFor(storeName).buffered().use { it.readString() }

    @Test
    fun storeNamesResolveInTheBaseDirectoryAndItIsCreatedOnFirstUse() {
        val baseDir = File(root, "app data/Snapshot PB")
        val provider = StoreIOProviderJvm(baseDir = baseDir, legacyDir = null)
        assertFalse(baseDir.exists(), "the directory is created on first use, not at construction")

        provider.write("devices.json", "[]")

        assertTrue(File(baseDir, "devices.json").isFile)
        assertEquals(File(baseDir, "devices.json").absolutePath, provider.pathFor("devices.json"))
    }

    @Test
    fun aLegacyDevicesFileMovesIntoTheBaseDirectoryOneTime() {
        val legacyDir = File(root, "cwd").apply { mkdirs() }
        File(legacyDir, "devices.json").writeText("[\"legacy\"]")
        val baseDir = File(root, "data")

        val provider = StoreIOProviderJvm(baseDir = baseDir, legacyDir = legacyDir)

        assertEquals("[\"legacy\"]", provider.read("devices.json"))
        assertFalse(File(legacyDir, "devices.json").exists(), "the old file moved, it was not copied")
    }

    @Test
    fun aLegacyFileDoesNotReplaceAFileThatIsAlreadyInTheBaseDirectory() {
        val legacyDir = File(root, "cwd").apply { mkdirs() }
        File(legacyDir, "devices.json").writeText("[\"legacy\"]")
        val baseDir = File(root, "data").apply { mkdirs() }
        File(baseDir, "devices.json").writeText("[\"current\"]")

        val provider = StoreIOProviderJvm(baseDir = baseDir, legacyDir = legacyDir)

        assertEquals("[\"current\"]", provider.read("devices.json"))
        assertTrue(File(legacyDir, "devices.json").exists())
    }

    @Test
    fun moveReplacesTheTargetAndRemovesTheSource() {
        val provider = StoreIOProviderJvm(baseDir = File(root, "data"), legacyDir = null)
        provider.write("devices.json", "old")
        provider.write("devices.json.tmp", "new")

        provider.move("devices.json.tmp", "devices.json")

        assertEquals("new", provider.read("devices.json"))
        assertFalse(provider.exists("devices.json.tmp"))
    }

    @Test
    fun deleteOfAMissingEntryDoesNotThrow() {
        StoreIOProviderJvm(baseDir = File(root, "data"), legacyDir = null).delete("absent")
    }

    @Test
    fun appDataDirectoryFollowsTheConventionOfEachOperatingSystem() {
        val noEnv: (String) -> String? = { null }

        assertEquals(
            File("/Users/u/Library/Application Support/Snapshot PB"),
            appDataDirectory(osName = "Mac OS X", env = noEnv, userHome = "/Users/u"),
        )
        assertEquals(
            File("C:\\Users\\u\\AppData\\Roaming", "Snapshot PB"),
            appDataDirectory(
                osName = "Windows 11",
                env = { if (it == "APPDATA") "C:\\Users\\u\\AppData\\Roaming" else null },
                userHome = "C:\\Users\\u",
            ),
        )
        assertEquals(
            File("/data/xdg/snapshot-pb"),
            appDataDirectory(
                osName = "Linux",
                env = { if (it == "XDG_DATA_HOME") "/data/xdg" else null },
                userHome = "/home/u",
            ),
        )
        assertEquals(
            File("/home/u/.local/share/snapshot-pb"),
            appDataDirectory(osName = "Linux", env = noEnv, userHome = "/home/u"),
        )
        // The XDG specification says to ignore a relative XDG_DATA_HOME.
        assertEquals(
            File("/home/u/.local/share/snapshot-pb"),
            appDataDirectory(osName = "Linux", env = { "relative/dir" }, userHome = "/home/u"),
        )
    }
}
