package com.snapshotpb.configuration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One firmware release of one board: an entry of the firmware site.
 *
 * The site generator (`scripts/pages_index.py` in the Firmware repository) writes this entry in
 * the `releases` list of `<board>/index.json`, and as the body of `<board>/latest.json`. It is not
 * the `<board>-<version>-manifest.json` asset of the GitHub Release: the generator takes the
 * fields of that file, checks the hashes, and adds the fields of the Release and the [assets].
 *
 * The site can add keys in a later version. The reader must ignore unknown keys, so every field
 * that the generator can leave out has a default.
 *
 * @property version The version text, for example `2026.43.1`. [firmwareVersion] parses it.
 * @property tag The git tag of the release, for example `v2026.43.1`.
 * @property channel The release channel, or `null` for a channel that this app does not know.
 *   The app offers a release with a `null` channel on no channel.
 * @property compatibleHardware The board names that can run the image. An empty list fits no
 *   board.
 * @property warning A text that the app shows with the release, or `null`.
 * @property assets The files of the release on the site.
 */
@Serializable
data class FirmwareManifest(
    val version: String,
    val tag: String = "",
    val channel: FirmwareChannel? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val built: String? = null,
    val commit: String? = null,
    @SerialName("compatible_hardware")
    val compatibleHardware: List<String> = emptyList(),
    val warning: String? = null,
    val assets: FirmwareAssets,
) {
    /** [version] as numbers, or `null` when the text is not a version that the app can compare. */
    val firmwareVersion: FirmwareVersion?
        get() = FirmwareVersion.parse(version)

    /** True when [compatibleHardware] holds the board name of [target]. */
    fun fits(target: HardwareTarget): Boolean = target.board in compatibleHardware

    /**
     * True when this release is newer than [boardVersion]. A release whose [version] does not parse
     * is newer than no version.
     */
    fun isNewerThan(boardVersion: FirmwareVersion): Boolean =
        firmwareVersion?.isNewerThan(boardVersion) ?: false

    /**
     * The offer rule: true when the app can offer this release to a board that reported [target]
     * and runs [boardVersion]. The release must [fit][fits] the target, and it must be
     * [newer][isNewerThan] than the version of the board.
     *
     * A `null` [target] is a board that did not answer `os info`, and a `null` [boardVersion] is a
     * board whose version the app cannot compare. Both get no offer.
     */
    fun isOfferFor(target: HardwareTarget?, boardVersion: FirmwareVersion?): Boolean =
        target != null && boardVersion != null && fits(target) && isNewerThan(boardVersion)
}

/**
 * The files of a release on the firmware site, by their kind.
 *
 * @property image The update image (`<board>-<version>-app.signed.bin`). The app sends this file
 *   to the board.
 * @property manifest The manifest of the GitHub Release.
 * @property sha256sums The `SHA256SUMS` file of the GitHub Release.
 */
@Serializable
data class FirmwareAssets(
    val image: FirmwareAsset,
    val manifest: FirmwareAsset? = null,
    val sha256sums: FirmwareAsset? = null,
)

/**
 * One file of a release on the firmware site.
 *
 * @property name The file name, the same as the asset name of the GitHub Release.
 * @property path The path of the file from the root of the site.
 * @property url The full URL of the file. The app downloads from this URL.
 * @property size The length of the file in bytes.
 * @property sha256 The SHA-256 of the file, in lowercase hexadecimal.
 */
@Serializable
data class FirmwareAsset(
    val name: String,
    val path: String = "",
    val url: String,
    val size: Long,
    val sha256: String,
)
