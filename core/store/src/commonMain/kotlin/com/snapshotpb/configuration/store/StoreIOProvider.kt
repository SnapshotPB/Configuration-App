package com.snapshotpb.configuration.store

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered

interface StoreIOProvider {
    fun exists(storeName: String): Boolean
    fun sinkFor(storeName: String): RawSink
    fun sourceFor(storeName: String): RawSource
    fun sourceForUri(uri: String): RawSource
    fun pathFor(storeName: String): String

    /**
     * Removes the stored entry if present. Best-effort and never throws — a store that cannot
     * delete (or an entry that is already gone) must not fail the caller's operation.
     */
    fun delete(storeName: String)

    /**
     * Moves the entry [from] to [to], and replaces an entry that is already at [to].
     *
     * A platform with a file system must do an atomic rename, so that a reader sees the full old
     * entry or the full new entry, and never a part of one. [DeviceStore] writes `devices.json` to
     * a temporary entry and then moves it here, so a process stop during a write cannot leave a
     * truncated file. Throws when the move fails. The entry at [to] then stays as it was.
     *
     * The default copies the bytes and then deletes [from]. It is correct for an in-memory store.
     */
    fun move(from: String, to: String) {
        sourceFor(from).buffered().use { source ->
            sinkFor(to).buffered().use { sink -> source.transferTo(sink) }
        }
        delete(from)
    }
}
