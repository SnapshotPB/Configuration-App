package com.snapshotpb.configuration.store

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Default dispatcher for [DeviceStore]'s blocking file IO. JVM/Android use [kotlinx.coroutines.Dispatchers.IO];
 * Kotlin/Native has no public `Dispatchers.IO`, so iOS falls back to [kotlinx.coroutines.Dispatchers.Default]
 * (the store's IO is small, infrequent, and guarded by a Mutex). A dispatcher can still be injected explicitly.
 */
internal expect val storeIoDispatcher: CoroutineDispatcher
