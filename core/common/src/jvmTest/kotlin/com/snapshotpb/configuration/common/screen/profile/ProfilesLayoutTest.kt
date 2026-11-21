package com.snapshotpb.configuration.common.screen.profile

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
        val lastProfileName = pendingDevice.knownProfiles.last().name
        val lastProfileMatcher = hasText(lastProfileName)
        val bannerTitle = runBlocking { getString(Res.string.device_pending_changes_banner_title) }
        val bannerMatcher = hasText(bannerTitle)
        val scrollTargets = listOf(ScrollTarget("last", lastProfileMatcher))

        for (size in DuoSize.entries) {
            val report = checkScreenLayout(
                screen = "profiles-pending",
                size = size,
                scrollTargets = scrollTargets,
                inspect = { inspectedSize ->
                    if (!fitsTwoPanes(inspectedSize.width, inspectedSize.height)) {
                        val lastProfileBounds = onNode(lastProfileMatcher, useUnmergedTree = true)
                            .fetchSemanticsNode()
                            .unclippedBoundsInRoot()
                        val bannerBounds = onNode(bannerMatcher, useUnmergedTree = true)
                            .fetchSemanticsNode()
                            .unclippedBoundsInRoot()
                        assertTrue(
                            lastProfileBounds.bottom < bannerBounds.top,
                            "${inspectedSize.label}: last profile bottom ${lastProfileBounds.bottom} px is not above pending banner top ${bannerBounds.top} px",
                        )
                    }
                },
            ) {
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
            assertEquals(fitsTwoPanes(size.width, size.height), report.twoPanes, size.label)
        }
    }
}
