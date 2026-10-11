package com.snapshotpb.configuration.store

import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Desktop (JVM) [StoreIOProvider].
 *
 * A relative store name resolves against [baseDir], a per-user application data directory (see
 * [appDataDirectory]). It does not resolve against the process working directory: a packaged app
 * that starts from Finder has `/` as its working directory, and on Windows the working directory
 * is often under Program Files. Neither is writable.
 *
 * On first use the provider creates [baseDir]. It also moves a `devices.json` that an older build
 * wrote to [legacyDir] (the working directory), one time, when [baseDir] has none.
 *
 * @param baseDir the directory for the stored files. Tests give a temporary directory.
 * @param legacyDir the directory where older builds wrote `devices.json`, or `null` for no move.
 */
class StoreIOProviderJvm(
    private val baseDir: File = appDataDirectory(),
    private val legacyDir: File? = System.getProperty("user.dir")?.let { File(it) },
) : StoreIOProvider {

    /** [baseDir], created and with the legacy files moved in on first access. */
    private val preparedBaseDir: File by lazy { prepareBaseDir() }

    override fun exists(storeName: String): Boolean =
        resolve(storeName).exists()

    override fun sinkFor(storeName: String): RawSink =
        SyncOnCloseOutputStream(FileOutputStream(resolve(storeName))).asSink()

    override fun sourceFor(storeName: String): RawSource =
        SystemFileSystem.source(Path(resolve(storeName).path))

    override fun sourceForUri(uri: String): RawSource =
        SystemFileSystem.source(
            path = SystemFileSystem.resolve(Path(uri))
        )

    override fun pathFor(storeName: String): String =
        resolve(storeName).absolutePath

    override fun delete(storeName: String) {
        runCatching { Files.deleteIfExists(resolve(storeName).toPath()) }
            .onFailure { error -> log.w(error) { "Cannot delete $storeName" } }
    }

    override fun move(from: String, to: String) {
        val source = resolve(from).toPath()
        val target = resolve(to).toPath()
        try {
            // A rename on the same file system. It replaces the target on POSIX and on Windows.
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            log.w(error) { "No atomic rename for $to. A plain replace is used." }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resolve(storeName: String): File {
        val file = File(storeName)
        return if (file.isAbsolute) file else File(preparedBaseDir, storeName)
    }

    private fun prepareBaseDir(): File {
        if (!baseDir.isDirectory && !baseDir.mkdirs()) {
            log.e { "Cannot create the application data directory ${baseDir.absolutePath}" }
        }
        legacyDir?.let { moveLegacyFiles(it) }
        return baseDir
    }

    /** Moves each file of [LEGACY_FILE_NAMES] from [legacy] into [baseDir] if [baseDir] has none. */
    private fun moveLegacyFiles(legacy: File) {
        val sameDirectory = runCatching { legacy.canonicalFile == baseDir.canonicalFile }.getOrDefault(false)
        if (sameDirectory) return
        for (name in LEGACY_FILE_NAMES) {
            val oldFile = File(legacy, name)
            val newFile = File(baseDir, name)
            if (!oldFile.isFile || newFile.exists()) continue
            try {
                // Without ATOMIC_MOVE, Files.move also copies across file systems.
                Files.move(oldFile.toPath(), newFile.toPath())
                log.i { "Moved ${oldFile.absolutePath} to ${newFile.absolutePath}" }
            } catch (error: IOException) {
                log.e(error) { "Cannot move ${oldFile.absolutePath} to ${newFile.absolutePath}" }
            }
        }
    }

    /**
     * Writes to a file, and on close flushes the bytes to the disk before the file closes. A move
     * that follows then cannot install a file whose bytes are still only in the OS cache.
     */
    private class SyncOnCloseOutputStream(private val out: FileOutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun flush() = out.flush()

        override fun close() {
            out.use { stream ->
                stream.flush()
                stream.fd.sync()
            }
        }
    }

    private companion object {
        /** Files that older builds wrote to the working directory. Desktop stores no photos. */
        val LEGACY_FILE_NAMES = listOf("devices.json")

        val log = Arbor.tag("StoreIOProviderJvm")
    }
}

/** Name of the application data directory on macOS and Windows. */
private const val APP_DIRECTORY_NAME = "Snapshot PB"

/** Name of the application data directory on Linux, in the XDG style. */
private const val APP_DIRECTORY_NAME_XDG = "snapshot-pb"

/**
 * The per-user application data directory for [osName]:
 * - macOS: `~/Library/Application Support/Snapshot PB`
 * - Windows: `%APPDATA%\Snapshot PB` (`~\AppData\Roaming` when `APPDATA` is not set)
 * - Linux and other systems: `$XDG_DATA_HOME/snapshot-pb` (`~/.local/share` when `XDG_DATA_HOME`
 *   is not set or is not absolute, as the XDG specification says)
 */
internal fun appDataDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home").orEmpty(),
): File {
    val os = osName.lowercase()
    return when {
        // Examine "mac" and "darwin" first, because "darwin" contains "win".
        "mac" in os || "darwin" in os ->
            File(File(userHome, "Library/Application Support"), APP_DIRECTORY_NAME)
        "win" in os -> {
            val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
            File(appData?.let { File(it) } ?: File(userHome, "AppData/Roaming"), APP_DIRECTORY_NAME)
        }
        else -> {
            val xdgDataHome = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
            File(xdgDataHome?.let { File(it) } ?: File(userHome, ".local/share"), APP_DIRECTORY_NAME_XDG)
        }
    }
}
