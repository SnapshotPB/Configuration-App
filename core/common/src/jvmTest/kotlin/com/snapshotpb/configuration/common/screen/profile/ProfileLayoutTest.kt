package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.RENDER_DENSITY
import com.snapshotpb.configuration.common.layout.ScrollTarget
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutProfiles
import com.snapshotpb.configuration.common.layout.unclippedBoundsInRoot
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_save_button
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Layout checks for [ProfileContent] and `ProfileTimingDiagram` at every [DuoSize]. */
class ProfileLayoutTest {

    private data class ProfileModeCase(
        val label: String,
        val profile: MarkerProfile,
        val boltMode: MarkerBoltMode,
    )

    private val profiles = layoutProfiles()

    private val modeCases = listOf(
        ProfileModeCase(
            label = "semi",
            profile = profiles.first(),
            boltMode = MarkerBoltMode.CLOSED,
        ),
        ProfileModeCase(
            label = "ramp-open",
            profile = profiles.first { it.fireMode == MarkerFireMode.CUSTOM_RAMPING },
            boltMode = MarkerBoltMode.OPEN,
        ),
    )

    @Test
    fun profileEditorFitsEveryDuoSize() {
        val saveLabel = runBlocking { getString(Res.string.configure_device_screen_save_button) }
        val scrollTargets = listOf(
            ScrollTarget("diagram", hasTestTag(PROFILE_TIMING_DIAGRAM_TAG)),
            ScrollTarget("save", hasText(saveLabel)),
        )

        for (mode in modeCases) {
            val found = ProfileLoad.Found(
                profile = mode.profile,
                boardProfile = mode.profile,
                boltMode = mode.boltMode,
            )
            for (size in DuoSize.entries) {
                val report = checkScreenLayout("profile-${mode.label}", size, scrollTargets) {
                    ProfileContent(
                        profile = mode.profile,
                        profileLoad = found,
                        boardProfile = mode.profile,
                        boltMode = mode.boltMode,
                        photoPath = "",
                        onProfileChange = {},
                        onCancel = {},
                        onSave = {},
                        onBack = {},
                    )
                }
                assertEquals(
                    fitsTwoPanes(size.width, size.height),
                    report.twoPanes,
                    "${mode.label} (${size.label})",
                )
            }
        }
    }

    /** Checks that `ProfileTimingDiagram` fills its scroll column and stays inside the window after scroll. */
    @Test
    fun timingDiagram_fitsItsColumn() {
        val diagramMatcher = hasTestTag(PROFILE_TIMING_DIAGRAM_TAG)
        val scrollTargets = listOf(ScrollTarget("diagram", diagramMatcher))

        for (mode in modeCases) {
            val found = ProfileLoad.Found(
                profile = mode.profile,
                boardProfile = mode.profile,
                boltMode = mode.boltMode,
            )
            for (size in DuoSize.entries) {
                checkScreenLayout(
                    screen = "profile-${mode.label}",
                    size = size,
                    scrollTargets = scrollTargets,
                    inspect = { inspectedSize ->
                        val diagramNode = onNode(diagramMatcher, useUnmergedTree = true).fetchSemanticsNode()
                        val diagramBounds = diagramNode.unclippedBoundsInRoot()
                        val scrollColumn = generateSequence(diagramNode.parent) { it.parent }
                            .firstOrNull {
                                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                            }
                        assertNotNull(
                            scrollColumn,
                            "${mode.label} (${inspectedSize.label}): no ancestor with VerticalScrollAxisRange",
                        )
                        val columnBounds = scrollColumn.unclippedBoundsInRoot()
                        val windowWidthPx = inspectedSize.width.value * RENDER_DENSITY
                        val windowHeightPx = inspectedSize.height.value * RENDER_DENSITY
                        val maxPaddingPx = MAX_DIAGRAM_HORIZONTAL_PADDING_DP * RENDER_DENSITY

                        assertTrue(
                            diagramBounds.width <= windowWidthPx,
                            "${mode.label} (${inspectedSize.label}): diagram width ${diagramBounds.width} px exceeds window width $windowWidthPx px",
                        )
                        assertTrue(
                            diagramBounds.width <= columnBounds.width + BOUNDS_TOLERANCE_PX &&
                                diagramBounds.width >= columnBounds.width - maxPaddingPx,
                            "${mode.label} (${inspectedSize.label}): diagram width ${diagramBounds.width} px does not fill column width ${columnBounds.width} px within $MAX_DIAGRAM_HORIZONTAL_PADDING_DP dp",
                        )
                        assertTrue(
                            diagramBounds.top >= 0f,
                            "${mode.label} (${inspectedSize.label}): diagram top ${diagramBounds.top} px is above the window",
                        )
                        assertTrue(
                            diagramBounds.bottom <= windowHeightPx,
                            "${mode.label} (${inspectedSize.label}): diagram bottom ${diagramBounds.bottom} px exceeds window height $windowHeightPx px",
                        )
                    },
                ) {
                    ProfileContent(
                        profile = mode.profile,
                        profileLoad = found,
                        boardProfile = mode.profile,
                        boltMode = mode.boltMode,
                        photoPath = "",
                        onProfileChange = {},
                        onCancel = {},
                        onSave = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_DIAGRAM_HORIZONTAL_PADDING_DP = 64f
        const val BOUNDS_TOLERANCE_PX = 1f
    }
}
