package com.snapshotpb.configuration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON format version of the firmware site that this app can read. The site generator
 * (`SCHEMA` in `scripts/pages_index.py`) increases it for a change that an older app cannot read.
 */
const val FIRMWARE_SITE_SCHEMA = 1

/**
 * The root `index.json` of the firmware site: the boards and the newest version of each channel.
 *
 * @property schema The JSON format version. See [FIRMWARE_SITE_SCHEMA].
 * @property boards The boards of the site, by their board name.
 */
@Serializable
data class FirmwareSiteIndex(
    val schema: Int = FIRMWARE_SITE_SCHEMA,
    val generated: String? = null,
    val repository: String? = null,
    val boards: Map<String, FirmwareSiteBoard> = emptyMap(),
)

/**
 * One board in the root `index.json` of the firmware site.
 *
 * @property index The path of the `index.json` of the board, from the root of the site.
 * @property latest The path of the `latest.json` of the board, or `null` when the board has no
 *   stable release.
 */
@Serializable
data class FirmwareSiteBoard(
    val index: String,
    val latest: String? = null,
    val channels: FirmwareChannelVersions = FirmwareChannelVersions(),
)

/** The newest version text of each channel, or `null` when the channel has no release. */
@Serializable
data class FirmwareChannelVersions(
    val stable: String? = null,
    val beta: String? = null,
)

/**
 * The `<board>/index.json` of the firmware site: every release of one board.
 *
 * @property baseUrl The URL of the site root, with a `/` at the end.
 * @property releases The releases of every channel, newest first.
 */
@Serializable
data class FirmwareBoardIndex(
    val schema: Int = FIRMWARE_SITE_SCHEMA,
    val board: String,
    val generated: String? = null,
    @SerialName("base_url")
    val baseUrl: String? = null,
    val channels: FirmwareChannelVersions = FirmwareChannelVersions(),
    val releases: List<FirmwareManifest> = emptyList(),
)
