package com.snapshotpb.configuration.model

import com.snapshotpb.configuration.model.FirmwareUpdateProgress.Downloading
import com.snapshotpb.configuration.model.FirmwareUpdateProgress.Uploading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareUpdateProgressTest {

    @Test
    fun theDownloadFractionNeedsTheLength() {
        assertEquals(0.5f, Downloading(received = 450, total = 900).fraction)
        assertNull(Downloading(received = 450, total = null).fraction)
        assertNull(Downloading(received = 0, total = 0).fraction)
    }

    @Test
    fun theUploadFractionStaysFromZeroToOne() {
        assertEquals(0f, Uploading(offset = 0, total = 900).fraction)
        assertEquals(0.25f, Uploading(offset = 225, total = 900).fraction)
        assertEquals(1f, Uploading(offset = 1000, total = 900).fraction)
        assertEquals(0f, Uploading(offset = 10, total = 0).fraction)
    }

    @Test
    fun aFailureOfTheBoardOrTheReleaseCannotBeRetried() {
        assertFalse(FirmwareUpdateFailure.NO_UPDATE_SERVICE.canRetry)
        assertFalse(FirmwareUpdateFailure.INCOMPATIBLE_HARDWARE.canRetry)
        assertFalse(FirmwareUpdateFailure.NO_RELEASE.canRetry)
        assertTrue(FirmwareUpdateFailure.LINK_LOST.canRetry)
        assertTrue(FirmwareUpdateFailure.NETWORK.canRetry)
    }
}
