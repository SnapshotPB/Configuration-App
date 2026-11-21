package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.ScrollTarget
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutDevice
import com.snapshotpb.configuration.common.layout.unclippedBoundsInRoot
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_pending_changes_banner_title
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Layout checks for [ProfilesContent] at every [DuoSize]. */
class ProfilesLayoutTest {

    private val connectedDevice = layoutDevice()

    private val pendingDevice = layoutDevice(diverged = true).let { base ->
        base.copy(
            knownProfiles = base.knownProfiles.take(3),
            boardState = base.boardState?.let { state ->
                state.copy(knownProfiles = state.knownProfiles.take(3))
            },
        )
    }

    private val completedProgress = ConnectionProgress(
        step1 = StepStatus.COMPLETED,
        step2 = StepStatus.COMPLETED,
        step3 = StepStatus.COMPLETED,
        step4 = StepStatus.COMPLETED,
    )

    @Test
    fun profilesConnectedFitsEveryDuoSize() {
        for (size in DuoSize.entries) {
            val report = checkScreenLayout("profiles-connected", size) {
                ProfilesContent(
                    device = connectedDevice,
                    connectionUiState = ConnectionUiState.Connected,
                    connectionProgress = completedProgress,
                    isConnected = true,
                    saveInFlight = false,
                    saveFailed = false,
                    actions = ProfilesActions(),
                )
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    @Test
    fun profilesPendingFitsEveryDuoSize() {
        val lastProfileMatcher = hasText(pendingDevice.knownProfiles.last().name)
        val bannerMatcher = hasText(runBlocking { getString(Res.string.device_pending_changes_banner_title) })
        val scrollTargets = listOf(ScrollTarget("last", lastProfileMatcher))

        for (size in DuoSize.entries) {
            val report = checkScreenLayout(
                screen = "profiles-pending",
                size = size,
                scrollTargets = scrollTargets,
                inspect = { inspectedSize ->
                    if (!fitsTwoPanes(inspectedSize.width, inspectedSize.height) &&
                        inspectedSize != DuoSize.OUTER_LANDSCAPE_SYMMETRIC
                    ) {
                        val (lastProfileBottom, bannerTop) = lastProfileAndBannerY(
                            lastProfileMatcher,
                            bannerMatcher,
                        )
                        assertTrue(
                            lastProfileBottom < bannerTop,
                            "${inspectedSize.label}: last profile bottom $lastProfileBottom px is not above banner top $bannerTop px",
                        )
                    }
                },
            ) {
                PendingProfiles()
            }
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }

    /**
     * In a one-column window 445 dp high, the 260 dp photo header and `PendingChangesCard` cover
     * the profile list. The plan records this as a known defect. When a change to the screen
     * corrects the defect, change this test to assert that the last profile clears the banner.
     */
    @Test
    fun pendingCard_coversListInShortOneColumnWindow_knownDefect() {
        val lastProfileMatcher = hasText(pendingDevice.knownProfiles.last().name)
        val bannerMatcher = hasText(runBlocking { getString(Res.string.device_pending_changes_banner_title) })
        val scrollTargets = listOf(ScrollTarget("last", lastProfileMatcher))

        checkScreenLayout(
            screen = "profiles-pending",
            size = DuoSize.OUTER_LANDSCAPE_SYMMETRIC,
            scrollTargets = scrollTargets,
            inspect = { inspectedSize ->
                val (lastProfileBottom, bannerTop) = lastProfileAndBannerY(
                    lastProfileMatcher,
                    bannerMatcher,
                )
                assertTrue(
                    lastProfileBottom >= bannerTop,
                    "${inspectedSize.label}: expected last profile bottom $lastProfileBottom px at or below banner top $bannerTop px",
                )
            },
        ) {
            PendingProfiles()
        }
    }

    @Composable
    private fun PendingProfiles() {
        ProfilesContent(
            device = pendingDevice,
            connectionUiState = ConnectionUiState.Disconnected,
            connectionProgress = ConnectionProgress(),
            isConnected = false,
            saveInFlight = false,
            saveFailed = false,
            actions = ProfilesActions(),
        )
    }

    private fun SemanticsNodeInteractionsProvider.lastProfileAndBannerY(
        lastProfileMatcher: SemanticsMatcher,
        bannerMatcher: SemanticsMatcher,
    ): Pair<Float, Float> {
        val lastProfileBottom = onNode(lastProfileMatcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .unclippedBoundsInRoot()
            .bottom
        val bannerTop = onNode(bannerMatcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .unclippedBoundsInRoot()
            .top
        return lastProfileBottom to bannerTop
    }
}
