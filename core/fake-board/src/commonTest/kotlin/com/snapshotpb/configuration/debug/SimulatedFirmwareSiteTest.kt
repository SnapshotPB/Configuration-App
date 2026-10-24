package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.firmware.FirmwareFeed
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The simulated site must give a release that the feed accepts: the Demo entry shows the update
 * only when the size and the SHA-256 of the image agree with the site.
 */
class SimulatedFirmwareSiteTest {

    private val feed = FirmwareFeed(http = SimulatedFirmwareSite(), siteUrl = SIMULATED_FIRMWARE_SITE_URL)

    @Test
    fun theSimulatedReleaseIsOfferedToTheSimulatedBoard() = runTest {
        val release = assertNotNull(feed.latest(SIMULATED_BOARD))

        assertEquals(SIMULATED_RELEASE_VERSION, release.version)
        assertTrue(
            release.isOfferFor(
                HardwareTarget.parse("snapshot_v2/esp32c3"),
                FirmwareVersion(year = 2026, week = 29, count = 0, dev = true),
            )
        )
    }

    @Test
    fun theBetaChannelGivesTheSameRelease() = runTest {
        assertEquals(SIMULATED_RELEASE_VERSION, feed.latest(SIMULATED_BOARD, FirmwareChannel.BETA)?.version)
    }

    @Test
    fun theImageHasTheSizeAndTheHashOfTheSite() = runTest {
        val release = assertNotNull(feed.latest(SIMULATED_BOARD))
        val progress = mutableListOf<Long>()

        val image = feed.download(release) { received, _ -> progress += received }

        assertContentEquals(simulatedImage(), image.bytes)
        assertEquals(SIMULATED_IMAGE_SHA256, image.manifest.assets.image.sha256)
        assertEquals(release.assets.image.size, progress.last())
    }
}
