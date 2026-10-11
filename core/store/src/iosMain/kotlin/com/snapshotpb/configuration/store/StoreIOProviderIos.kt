@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.snapshotpb.configuration.store

import com.toxicbakery.logging.Arbor
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS [StoreIOProvider] backed by kotlinx-io [SystemFileSystem].
 *
 * Relative store names are rooted at the app sandbox Application Support directory. Unlike the
 * Documents directory, Application Support is not created automatically, so it is created on first
 * use. Mirrors the semantics of the JVM implementation.
 *
 * Backup policy: iCloud and computer backups include the store on purpose. The owner decided this
 * for #135, so that a restore to a new iPhone keeps the known boards, their profiles and their
 * photos. The directory thus has no `NSURLIsExcludedFromBackupKey`. The store holds no secret.
 */
class StoreIOProviderIos : StoreIOProvider {

    private val baseDir: String = resolveApplicationSupportDirectory()

    override fun exists(storeName: String): Boolean =
        SystemFileSystem.exists(resolve(storeName))

    override fun sinkFor(storeName: String): RawSink =
        SystemFileSystem.sink(resolve(storeName))

    override fun sourceFor(storeName: String): RawSource =
        SystemFileSystem.source(resolve(storeName))

    override fun sourceForUri(uri: String): RawSource =
        SystemFileSystem.source(Path(uri.removePrefix(FILE_URI_SCHEME)))

    override fun pathFor(storeName: String): String =
        resolve(storeName).toString()

    override fun delete(storeName: String) {
        runCatching { SystemFileSystem.delete(resolve(storeName), mustExist = false) }
    }

    /** An atomic `rename`, which replaces the file at [to]. */
    override fun move(from: String, to: String) {
        SystemFileSystem.atomicMove(resolve(from), resolve(to))
    }

    private fun resolve(storeName: String): Path {
        val path = Path(storeName)
        return if (path.isAbsolute) path else Path(baseDir, storeName)
    }

    private companion object {
        private const val FILE_URI_SCHEME = "file://"

        private fun resolveApplicationSupportDirectory(): String {
            val fileManager = NSFileManager.defaultManager
            val directoryUrl = fileManager.URLsForDirectory(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
            ).firstOrNull() as? NSURL
            val path = directoryUrl?.path
                ?: error("Unable to resolve the application support directory.")
            if (!fileManager.fileExistsAtPath(path)) {
                val created = fileManager.createDirectoryAtPath(
                    path = path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
                if (!created) {
                    Arbor.tag("StoreIOProviderIos").e { "Failed to create Application Support directory at $path" }
                }
            }
            return path
        }
    }
}
