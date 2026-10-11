package com.snapshotpb.configuration.bluetooth

import kotlin.uuid.Uuid

/**
 * Converts a platform Bluetooth peripheral identifier into a stable [Uuid].
 *
 * Android exposes peripherals by MAC address (e.g. "AC:EB:E6:81:A8:5C"), which is not a
 * valid UUID and cannot be passed to [Uuid.parse]. The conversion is deterministic, so the
 * same peripheral always maps to the same [Uuid] across scans and app launches.
 */
fun String.toDeviceUuid(): Uuid {
    Uuid.parseOrNull(this)?.let { return it }

    var msb = 1125899906842597L
    var lsb = -3750763034362895579L
    for (char in this) {
        msb = msb * 31 + char.code
        lsb = lsb * 131 + char.code
    }
    return Uuid.fromLongs(msb, lsb)
}
