package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.store.devicePhotoStoreName

/**
 * The store name for a new photo of the device [deviceId]. The name has a [stamp], so each new
 * photo gets a new `photoPath`. With one fixed name, a new photo overwrote the old file and left
 * the path the same. The store then did not emit, the Wear sync did not run, and Coil showed the
 * old image from its memory cache.
 *
 * A file name, and not a `?v=` query, carries the version: the fallback file read of the Wear
 * sync takes the last path segment as the file name, and a query would break that read.
 */
internal fun versionedDevicePhotoStoreName(deviceId: String, stamp: Long): String =
    "${devicePhotoStoreName(deviceId)}_$stamp"
