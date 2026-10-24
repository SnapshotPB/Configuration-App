package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareManifestTest {

    private val v2 = HardwareTarget.parse("snapshot_v2/esp32c3")
    private val reva = HardwareTarget.parse("snapshot_reva/esp32c3")
    private val boardRelease = FirmwareVersion(2026, 43, 1, dev = false)

    private fun release(
        version: String = "2026.52.1",
        compatibleHardware: List<String> = listOf("snapshot_v2"),
    ) = FirmwareManifest(
        version = version,
        tag = "v$version",
        channel = FirmwareChannel.STABLE,
        compatibleHardware = compatibleHardware,
        assets = FirmwareAssets(
            image = FirmwareAsset(
                name = "snapshot_v2-$version-app.signed.bin",
                url = "https://snapshotpb.github.io/Firmware/snapshot_v2/$version/snapshot_v2-$version-app.signed.bin",
                size = 43,
                sha256 = "f54293faeea163ee474b21c5ae7188bafd1fff82243d2f8e10fafa12f7749bef",
            ),
        ),
    )

    @Test
    fun theVersionTextParses() {
        assertEquals(FirmwareVersion(2026, 52, 1, dev = false), release().firmwareVersion)
        assertNull(release(version = "latest").firmwareVersion)
    }

    @Test
    fun offersANewerReleaseThatFitsTheBoard() {
        assertTrue(release().isOfferFor(v2, boardRelease))
    }

    @Test
    fun offersNothingToABoardThatTheReleaseDoesNotName() {
        assertFalse(release().fits(assertNotNull(reva)))
        assertFalse(release().isOfferFor(reva, boardRelease))
    }

    @Test
    fun offersNothingWhenTheReleaseNamesNoHardware() {
        // The site gives a release with no compatible_hardware in its manifest an empty list.
        assertFalse(release(compatibleHardware = emptyList()).isOfferFor(v2, boardRelease))
    }

    @Test
    fun offersARelease_toEachBoardThatItNames() {
        val both = release(compatibleHardware = listOf("snapshot_reva", "snapshot_v2"))
        assertTrue(both.isOfferFor(v2, boardRelease))
        assertTrue(both.isOfferFor(reva, boardRelease))
    }

    @Test
    fun offersNothingWhenTheReleaseIsNotNewer() {
        assertFalse(release(version = "2026.43.1").isOfferFor(v2, boardRelease))
        assertFalse(release(version = "2026.40.1").isOfferFor(v2, boardRelease))
    }

    @Test
    fun offersTheReleaseOfTheSameNumbers_toADevBuild() {
        val devBoard = FirmwareVersion(2026, 43, 1, dev = true)
        assertTrue(release(version = "2026.43.1").isOfferFor(v2, devBoard))
    }

    @Test
    fun offersNothingWhenAVersionIsNotKnown() {
        // No os info answer: the firmware of the board has no update service.
        assertFalse(release().isOfferFor(null, boardRelease))
        // An older advertisement with a text version: the app cannot compare it.
        assertFalse(release().isOfferFor(v2, null))
        // A version text on the site that does not parse.
        assertFalse(release(version = "latest").isOfferFor(v2, boardRelease))
    }

    @Test
    fun anImageIsEqualByItsBytes() {
        val manifest = release()
        val image = FirmwareImage(manifest, byteArrayOf(1, 2, 3))
        assertEquals(image, FirmwareImage(manifest, byteArrayOf(1, 2, 3)))
        assertEquals(image.hashCode(), FirmwareImage(manifest, byteArrayOf(1, 2, 3)).hashCode())
        assertNotEquals(image, FirmwareImage(manifest, byteArrayOf(1, 2, 4)))
        assertEquals(FirmwareVersion(2026, 52, 1, dev = false), image.version)
    }
}
