package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.firmware.FIRMWARE_SITE_URL
import com.snapshotpb.configuration.firmware.FirmwareFeed
import com.snapshotpb.configuration.firmware.FirmwareHttp
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FirmwareModuleTest {

    /** The binding that each app adds with the engine of its platform. */
    private object NoNetwork : FirmwareHttp {
        override suspend fun getText(url: String): String = error("no network in this test")

        override suspend fun getBytes(
            url: String,
            maxBytes: Long,
            onProgress: (received: Long, total: Long?) -> Unit,
        ): ByteArray = error("no network in this test")
    }

    @Test
    fun theCoreGraphGivesOneFeedOfTheFirmwareSite() {
        val di = DI {
            import(coreDiModule)
            bindSingleton<FirmwareHttp> { NoNetwork }
        }

        val feed = di.direct.instance<FirmwareFeed>()

        assertEquals(FIRMWARE_SITE_URL, feed.siteUrl)
        assertSame(feed, di.direct.instance<FirmwareFeed>())
    }
}
