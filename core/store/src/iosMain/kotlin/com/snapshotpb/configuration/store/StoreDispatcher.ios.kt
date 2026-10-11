package com.snapshotpb.configuration.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Native has no public Dispatchers.IO. Confine the store's blocking file IO to a single
// slot so it can't starve the shared CPU-bound Default pool (the store also serializes via a Mutex).
internal actual val storeIoDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
