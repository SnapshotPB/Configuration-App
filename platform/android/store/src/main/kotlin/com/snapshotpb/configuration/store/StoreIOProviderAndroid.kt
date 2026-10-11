package com.snapshotpb.configuration.store

import android.content.Context
import android.net.Uri
import com.toxicbakery.logging.Arbor
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class StoreIOProviderAndroid(
    private val context: Context,
) : StoreIOProvider {
    override fun exists(storeName: String): Boolean =
        context.getFileStreamPath(storeName).exists()

    override fun sinkFor(storeName: String): RawSink =
        SyncOnCloseOutputStream(context.openFileOutput(storeName, Context.MODE_PRIVATE)).asSink()

    override fun sourceFor(storeName: String): RawSource =
        context.openFileInput(storeName).asSource()

    override fun sourceForUri(uri: String): RawSource {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw IOException("Unable to open input stream for URI: $uri")
        return stream.asSource()
    }

    override fun pathFor(storeName: String): String =
        context.getFileStreamPath(storeName).absolutePath

    override fun delete(storeName: String) {
        // The interface says "never throws", so a failure is logged only.
        try {
            if (!context.deleteFile(storeName) && exists(storeName)) {
                log.w { "Unable to delete $storeName" }
            }
        } catch (error: SecurityException) {
            log.w(error) { "Unable to delete $storeName" }
        }
    }

    /**
     * A `rename` in the app files directory. It is atomic and replaces the file at [to], like the
     * rename that `android.util.AtomicFile` does.
     */
    override fun move(from: String, to: String) {
        val source = context.getFileStreamPath(from)
        val target = context.getFileStreamPath(to)
        if (!source.renameTo(target)) {
            throw IOException("Unable to rename $from to $to")
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
        val log = Arbor.tag("StoreIOProviderAndroid")
    }
}
