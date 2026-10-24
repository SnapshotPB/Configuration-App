package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.firmware.FIRMWARE_SITE_URL
import com.snapshotpb.configuration.firmware.FirmwareFeed
import com.snapshotpb.configuration.firmware.FirmwareHttp
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /** The graph of an app that updates firmware, with the three bindings of the app. */
    private fun appGraph(policy: FirmwareUpdatePolicy = FirmwareUpdatePolicy()) = DI {
        import(coreDiModule)
        import(firmwareModule)
        bindSingleton<FirmwareHttp> { NoNetwork }
        bindSingleton { policy }
        bindSingleton { BluetoothDeviceStateSource { flowOf(BluetoothDeviceState()) } }
    }

    @Test
    fun theAppGraphGivesOneFeedOfTheFirmwareSite() {
        val di = appGraph()

        val feed = di.direct.instance<FirmwareFeed>()

        assertEquals(FIRMWARE_SITE_URL, feed.siteUrl)
        assertSame(feed, di.direct.instance<FirmwareFeed>())
    }

    @Test
    fun aLocalSiteOfThePolicyIsTheSiteOfTheFeed() {
        val di = appGraph(FirmwareUpdatePolicy(siteUrl = "http://localhost:8000/"))

        assertEquals("http://localhost:8000", di.direct.instance<FirmwareFeed>().siteUrl)
    }

    @Test
    fun theAppGraphGivesOneCoordinator() {
        val di = appGraph()

        assertSame(di.direct.instance<FirmwareUpdateCoordinator>(), di.direct.instance<FirmwareUpdateCoordinator>())
    }

    @Test
    fun theCoreGraphOfTheWearAppHasNoFirmwareUpdate() {
        // The Wear app imports coreDiModule and binds no FirmwareHttp, so the core graph must not
        // hold the feed or the coordinator.
        val di = DI { import(coreDiModule) }

        assertNull(di.direct.instanceOrNull<FirmwareFeed>())
        assertNull(di.direct.instanceOrNull<FirmwareUpdateCoordinator>())
    }
}
