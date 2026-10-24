package com.snapshotpb.configuration.model

/**
 * A downloaded update image whose size and SHA-256 agree with [manifest].
 *
 * This is not a stored model, so it is not `@Serializable`: the app keeps the image in memory
 * only, for the upload to the board. It is in `core:model` because both the firmware feed and the
 * Bluetooth layer use it.
 *
 * Do not change the content of [bytes]. The feed checked them against the hash of the site.
 */
class FirmwareImage(
    val manifest: FirmwareManifest,
    val bytes: ByteArray,
) {
    /** The version of the image, or `null` when the version text of [manifest] does not parse. */
    val version: FirmwareVersion?
        get() = manifest.firmwareVersion

    override fun equals(other: Any?): Boolean =
        other is FirmwareImage && manifest == other.manifest && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * manifest.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "FirmwareImage(version=${manifest.version}, size=${bytes.size})"
}
