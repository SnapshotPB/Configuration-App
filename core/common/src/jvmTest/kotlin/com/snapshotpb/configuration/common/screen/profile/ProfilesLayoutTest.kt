package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
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
import com.snapshotpb.core.resources.profile_badge_description
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertFalse
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
            checkScreenLayout("profiles-connected", size) {
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
        }
    }

    @Test
    fun profilesPendingFitsEveryDuoSize() {
        val lastProfileMatcher = hasText(pendingDevice.knownProfiles.last().name)
        val headerMatcher = hasContentDescription(runBlocking { getString(Res.string.profile_badge_description) })
        val bannerMatcher = hasText(runBlocking { getString(Res.string.device_pending_changes_banner_title) })
        val scrollTargets = listOf(ScrollTarget("last", lastProfileMatcher, toEnd = true))

        for (size in DuoSize.entries) {
            checkScreenLayout(
                screen = "profiles-pending",
                size = size,
                scrollTargets = scrollTargets,
                inspect = { inspectedSize ->
                    if (!fitsTwoPanes(inspectedSize.width, inspectedSize.height) &&
                        inspectedSize != DuoSize.OUTER_LANDSCAPE_SIDE_BAR
                    ) {
                        val position = lastProfilePosition(
                            lastProfileMatcher = lastProfileMatcher,
                            headerMatcher = headerMatcher,
                            bannerMatcher = bannerMatcher,
                        )
                        assertTrue(
                            position.visible,
                            "${inspectedSize.label}: last profile [${position.profileTop}, ${position.profileBottom}] is outside header bottom ${position.headerBottom} and banner top ${position.bannerTop}",
                        )
                    }
                },
            ) {
                PendingProfiles()
            }
        }
    }

    /**
     * In a one-column window 466 dp high, the 260 dp photo header and `PendingChangesCard` cover
     * the profile list. The plan records this as a known defect. When a change to the screen
     * corrects the defect, change this test to assert that the last profile clears the banner.
     */
    @Test
    fun pendingCard_coversListInShortOneColumnWindow_knownDefect() {
        val lastProfileMatcher = hasText(pendingDevice.knownProfiles.last().name)
        val headerMatcher = hasContentDescription(runBlocking { getString(Res.string.profile_badge_description) })
        val bannerMatcher = hasText(runBlocking { getString(Res.string.device_pending_changes_banner_title) })
        val scrollTargets = listOf(ScrollTarget("last", lastProfileMatcher, toEnd = true))

        checkScreenLayout(
            screen = "profiles-pending-defect",
            size = DuoSize.OUTER_LANDSCAPE_SIDE_BAR,
            scrollTargets = scrollTargets,
            inspect = { inspectedSize ->
                val position = lastProfilePosition(
                    lastProfileMatcher = lastProfileMatcher,
                    headerMatcher = headerMatcher,
                    bannerMatcher = bannerMatcher,
                )
                assertFalse(
                    position.visible,
                    "${inspectedSize.label}: expected last profile [${position.profileTop}, ${position.profileBottom}] to be covered by header bottom ${position.headerBottom} or banner top ${position.bannerTop}",
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

    private data class LastProfilePosition(
        val profileTop: Float,
        val profileBottom: Float,
        val headerBottom: Float,
        val bannerTop: Float,
    ) {
        val visible: Boolean
            get() = profileTop >= headerBottom && profileBottom <= bannerTop
    }

    private fun SemanticsNodeInteractionsProvider.lastProfilePosition(
        lastProfileMatcher: SemanticsMatcher,
        headerMatcher: SemanticsMatcher,
        bannerMatcher: SemanticsMatcher,
    ): LastProfilePosition {
        val profileBounds = onNode(lastProfileMatcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .unclippedBoundsInRoot()
        val headerBottom = onNode(headerMatcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .unclippedBoundsInRoot()
            .bottom
        val bannerTop = onNode(bannerMatcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .unclippedBoundsInRoot()
            .top
        return LastProfilePosition(
            profileTop = profileBounds.top,
            profileBottom = profileBounds.bottom,
            headerBottom = headerBottom,
            bannerTop = bannerTop,
        )
    }
}
