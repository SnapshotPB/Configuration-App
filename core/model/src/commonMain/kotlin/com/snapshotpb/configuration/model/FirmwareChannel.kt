package com.snapshotpb.configuration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The release channel of a firmware release on the firmware site.
 *
 * The site generator (`scripts/pages_index.py` in the Firmware repository) puts a GitHub
 * pre-release in [BETA] and every other release in [STABLE].
 */
@Serializable
enum class FirmwareChannel {
    /** A release for every board. `<board>/latest.json` holds the newest one. */
    @SerialName("stable")
    STABLE,

    /** A GitHub pre-release. Only `<board>/index.json` holds these releases. */
    @SerialName("beta")
    BETA,
}
