package com.snapshotpb.configuration.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Wasm is single-threaded and has no public Dispatchers.IO. The store's IO is small,
// infrequent localStorage access guarded by a Mutex, so it runs on the browser event loop via
// Dispatchers.Default (the blocking-IO concerns that motivate a dedicated pool don't exist here).
internal actual val storeIoDispatcher: CoroutineDispatcher = Dispatchers.Default
