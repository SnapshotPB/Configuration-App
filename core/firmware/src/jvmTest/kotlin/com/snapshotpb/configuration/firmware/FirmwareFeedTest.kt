package com.snapshotpb.configuration.firmware

import com.snapshotpb.configuration.firmware.FakeFirmwareSite.Companion.fixtureText
import com.snapshotpb.configuration.firmware.FakeFirmwareSite.Reply
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [FirmwareFeed] against the output of the firmware site generator. [FakeFirmwareSite] gives
 * the fixture tree in `src/jvmTest/resources/pages` (see the README there) at the site URLs.
 */
class FirmwareFeedTest {

    private val site = FakeFirmwareSite()
    private val dispatcher = StandardTestDispatcher()
    private val feed = FirmwareFeed(http = site, hashDispatcher = dispatcher)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    @Test
    fun theStableChannelReadsLatestJson() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))

        assertEquals(listOf("$SITE/snapshot_v2/latest.json"), site.requests)
        assertEquals("2026.52.1", release.version)
        assertEquals("v2026.52.1", release.tag)
        assertEquals(FirmwareChannel.STABLE, release.channel)
        assertEquals("2026-12-21T10:05:00Z", release.publishedAt)
        assertEquals("2222222222222222222222222222222222222222", release.commit)
        assertEquals(listOf("snapshot_v2"), release.compatibleHardware)
        assertEquals("MARKER-REVISION SPECIFIC -- flash only to snapshot_v2.", release.warning)
        assertEquals(43, release.assets.image.size)
        assertEquals("$SITE/snapshot_v2/2026.52.1/snapshot_v2-2026.52.1-app.signed.bin", release.assets.image.url)
        assertEquals("snapshot_v2/2026.52.1/snapshot_v2-2026.52.1-SHA256SUMS", release.assets.sha256sums?.path)
    }

    @Test
    fun theStableReleaseIsOfferedToAnOlderBoardOfTheSameHardware() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))

        assertTrue(release.isOfferFor(HardwareTarget.parse("snapshot_v2/esp32c3"), FirmwareVersion(2026, 43, 1, false)))
        assertFalse(release.isOfferFor(HardwareTarget.parse("snapshot_reva/esp32c3"), FirmwareVersion(2026, 43, 1, false)))
        assertFalse(release.isOfferFor(HardwareTarget.parse("snapshot_v2/esp32c3"), FirmwareVersion(2026, 52, 1, false)))
    }

    @Test
    fun aGoodImageComesBackWithItsBytes() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        val progress = mutableListOf<Pair<Long, Long?>>()

        val image = feed.download(release) { received, total -> progress += received to total }

        assertEquals(release, image.manifest)
        assertContentEquals("fake update image of snapshot_v2 2026.52.1\n".encodeToByteArray(), image.bytes)
        assertEquals(43L to 43L, progress.last())
    }

    @Test
    fun aBadHashStopsTheDownload() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        // The same length as the image of the site, with other bytes.
        site.replies[release.assets.image.url] = Reply.Bytes(ByteArray(43) { 'x'.code.toByte() })

        val error = assertFailsWith<FirmwareFeedException.BadHash> { feed.download(release) }

        assertEquals(release.assets.image.sha256, error.expected)
        assertEquals(release.assets.image.url, error.url)
    }

    @Test
    fun anImageOfAnotherLengthStopsTheDownload() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        site.replies[release.assets.image.url] = Reply.Bytes(ByteArray(42))

        val error = assertFailsWith<FirmwareFeedException.BadSize> { feed.download(release) }

        assertEquals(43, error.expected)
        assertEquals(42, error.actual)
    }

    @Test
    fun aSecondDownloadOfTheSameImageUsesTheImageInMemory() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        val first = feed.download(release)
        // A change of the copy that a caller has must not change the image in memory.
        first.bytes.fill(0)

        val second = feed.download(release)

        assertEquals(1, site.requests.count { it == release.assets.image.url })
        assertContentEquals("fake update image of snapshot_v2 2026.52.1\n".encodeToByteArray(), second.bytes)
    }

    @Test
    fun aBadImageIsNotKeptInMemory() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        site.replies[release.assets.image.url] = Reply.Bytes(ByteArray(43))
        assertFailsWith<FirmwareFeedException.BadHash> { feed.download(release) }
        site.replies.remove(release.assets.image.url)

        feed.download(release)

        assertEquals(2, site.requests.count { it == release.assets.image.url })
    }

    @Test
    fun aBoardThatTheSiteDoesNotHaveHasNoRelease() = test {
        assertNull(feed.latest("snapshot_reva"))
        assertNull(feed.latest("snapshot_reva", FirmwareChannel.BETA))
        assertNull(feed.releases("snapshot_reva"))
    }

    @Test
    fun aBoardNameThatTheSiteCannotHoldMakesNoRequest() = test {
        assertNull(feed.latest("../snapshot_v2"))
        assertNull(feed.latest("Snapshot V2"))
        assertNull(feed.releases(""))
        assertTrue(site.requests.isEmpty())
    }

    @Test
    fun anotherHttpFailureIsAnError() = test {
        site.replies["$SITE/snapshot_v2/latest.json"] = Reply.Status(503)

        val error = assertFailsWith<FirmwareFeedException.Http> { feed.latest("snapshot_v2") }

        assertEquals(503, error.status)
    }

    @Test
    fun theBetaChannelTakesTheNewestReleaseOfTheBoardIndex() = test {
        val release = assertNotNull(feed.latest("snapshot_v2", FirmwareChannel.BETA))

        assertEquals(listOf("$SITE/snapshot_v2/index.json"), site.requests)
        assertEquals("2027.1.1", release.version)
        assertEquals(FirmwareChannel.BETA, release.channel)
        val image = feed.download(release)
        assertContentEquals("fake update image of snapshot_v2 2027.1.1\n".encodeToByteArray(), image.bytes)
    }

    @Test
    fun theBetaChannelTakesAStableReleaseThatIsNewer() = test {
        val index = Json.parseToJsonElement(fixtureText("snapshot_v2/index.json")).jsonObject
        val releases = index.getValue("releases") as JsonArray
        // Remove the beta release 2027.1.1, so the stable 2026.52.1 is the newest release.
        site.replies["$SITE/snapshot_v2/index.json"] =
            Reply.Text(JsonObject(index + ("releases" to JsonArray(releases.drop(1)))).toString())

        assertEquals("2026.52.1", feed.latest("snapshot_v2", FirmwareChannel.BETA)?.version)
    }

    @Test
    fun theBoardIndexHoldsEveryReleaseNewestFirst() = test {
        val index = assertNotNull(feed.releases("snapshot_v2"))

        assertEquals("snapshot_v2", index.board)
        assertEquals("https://snapshotpb.github.io/Firmware/", index.baseUrl)
        assertEquals("2026.52.1", index.channels.stable)
        assertEquals("2027.1.1", index.channels.beta)
        assertEquals(listOf("2027.1.1", "2026.52.1", "2026.9.3"), index.releases.map { it.version })
        val noHardware = index.releases.last()
        assertEquals(emptyList(), noHardware.compatibleHardware)
        assertEquals("no compatible hardware declared", noHardware.warning)
        assertFalse(noHardware.isOfferFor(HardwareTarget.parse("snapshot_v2/esp32c3"), FirmwareVersion(2026, 1, 1, false)))
    }

    @Test
    fun theRootIndexHoldsTheBoards() = test {
        val index = feed.boards()

        assertEquals(1, index.schema)
        assertEquals("SnapshotPB/Firmware", index.repository)
        assertEquals(setOf("snapshot_reva", "snapshot_v2"), index.boards.keys)
        val reva = index.boards.getValue("snapshot_reva")
        assertEquals("snapshot_reva/latest.json", reva.latest)
        assertEquals("2026.52.1", reva.channels.stable)
        assertNull(reva.channels.beta)
        assertEquals("snapshot_v2/index.json", index.boards.getValue("snapshot_v2").index)
    }

    @Test
    fun unknownKeysOfTheSiteAreIgnored() = test {
        val latest = Json.parseToJsonElement(fixtureText("snapshot_v2/latest.json")).jsonObject
        val assets = latest.getValue("assets").jsonObject
        val image = assets.getValue("image").jsonObject
        val changed = JsonObject(
            latest + mapOf(
                "min_app_version" to JsonPrimitive("2027.1.0"),
                "signature" to buildJsonObject { put("ed25519", "abc") },
                "assets" to JsonObject(
                    assets + mapOf(
                        "image" to JsonObject(image + ("content_type" to JsonPrimitive("application/octet-stream"))),
                        "merged_hex" to buildJsonObject {
                            put("name", "snapshot_v2-2026.52.1-merged.hex")
                            put("url", "$SITE/snapshot_v2/2026.52.1/snapshot_v2-2026.52.1-merged.hex")
                            put("size", 2500000)
                            put("sha256", "0".repeat(64))
                        },
                    ),
                ),
            ),
        )
        site.replies["$SITE/snapshot_v2/latest.json"] = Reply.Text(changed.toString())

        val release = assertNotNull(feed.latest("snapshot_v2"))

        assertEquals("2026.52.1", release.version)
        assertEquals(43, release.assets.image.size)
        feed.download(release)
    }

    @Test
    fun anUnknownChannelIsNoChannel() = test {
        val latest = Json.parseToJsonElement(fixtureText("snapshot_v2/latest.json")).jsonObject
        site.replies["$SITE/snapshot_v2/latest.json"] =
            Reply.Text(JsonObject(latest + ("channel" to JsonPrimitive("nightly"))).toString())

        assertNull(assertNotNull(feed.latest("snapshot_v2")).channel)
    }

    @Test
    fun aNewerSchemaIsRefused() = test {
        val latest = Json.parseToJsonElement(fixtureText("snapshot_v2/latest.json")).jsonObject
        site.replies["$SITE/snapshot_v2/latest.json"] =
            Reply.Text(JsonObject(latest + ("schema" to JsonPrimitive(2))).toString())

        val error = assertFailsWith<FirmwareFeedException.UnsupportedSchema> { feed.latest("snapshot_v2") }

        assertEquals(2, error.schema)
    }

    @Test
    fun aFileThatIsNotTheSiteFormatIsBadJson() = test {
        site.replies["$SITE/snapshot_v2/latest.json"] = Reply.Text("<html>Not Found</html>")
        assertFailsWith<FirmwareFeedException.BadJson> { feed.latest("snapshot_v2") }

        site.replies["$SITE/snapshot_v2/latest.json"] = Reply.Text("""{"schema": 1, "version": "2026.52.1"}""")
        assertFailsWith<FirmwareFeedException.BadJson> { feed.latest("snapshot_v2") }
    }

    @Test
    fun aReleaseWithNoValidHashIsNotDownloaded() = test {
        val release = assertNotNull(feed.latest("snapshot_v2"))
        val noHash = release.copy(assets = release.assets.copy(image = release.assets.image.copy(sha256 = "")))

        assertFailsWith<FirmwareFeedException.BadJson> { feed.download(noHash) }

        assertFalse(site.requests.contains(release.assets.image.url))
    }

    @Test
    fun anotherSiteUrlChangesEveryRequest() = test {
        val local = FakeFirmwareSite(siteUrl = "http://localhost:8000/site/")
        val localFeed = FirmwareFeed(http = local, siteUrl = "http://localhost:8000/site/", hashDispatcher = dispatcher)

        assertEquals("http://localhost:8000/site", localFeed.siteUrl)
        assertNotNull(localFeed.latest("snapshot_v2"))
        assertEquals(listOf("http://localhost:8000/site/snapshot_v2/latest.json"), local.requests)
    }

    private companion object {
        const val SITE = FIRMWARE_SITE_URL
    }
}
