package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * How bright the board drives its screen. Mirrors the protocol's `ScreenBrightness` enum.
 *
 * This is a board setting and not a profile setting: one screen serves every profile slot. The
 * protocol held it on `Profile` until it moved to `DeviceConfig`, so a board updated across that
 * move forgets its brightness once and the user sets it again.
 *
 * A device holds `null` when the board has not reported a brightness: firmware from before the
 * field omits it, which decodes as `SCREEN_BRIGHTNESS_UNSPECIFIED`. A `null` write leaves the
 * board's brightness unchanged.
 */
@Serializable
enum class BoardScreenBrightness {
    LOW,
    DEFAULT,
    HIGH,
}
