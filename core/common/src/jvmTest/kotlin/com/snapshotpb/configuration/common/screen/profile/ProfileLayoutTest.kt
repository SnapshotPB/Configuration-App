package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.layout.BOUNDS_TOLERANCE_PX
import com.snapshotpb.configuration.common.layout.DuoSize
import com.snapshotpb.configuration.common.layout.RENDER_DENSITY
import com.snapshotpb.configuration.common.layout.ScrollTarget
import com.snapshotpb.configuration.common.layout.checkScreenLayout
import com.snapshotpb.configuration.common.layout.layoutProfiles
import com.snapshotpb.configuration.common.layout.unclippedBoundsInRoot
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_save_button
import com.snapshotpb.core.resources.profile_eye_hint_eye_off
import com.snapshotpb.core.resources.profile_eye_hint_fault
import com.snapshotpb.core.resources.profile_eye_hint_not_permitted
import com.snapshotpb.core.resources.profile_eye_hint_override_off
import com.snapshotpb.core.resources.profile_eye_hint_unsupported
import com.snapshotpb.core.resources.profile_eye_result_ball
import com.snapshotpb.core.resources.profile_eye_result_empty
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            for (size in DuoSize.entries) {
                checkScreenLayout("profile-${mode.label}", size, scrollTargets) {
                    SampleProfile(mode)
                }
            }
        }
    }

    /** Checks that `ProfileTimingDiagram` fills its scroll column and stays inside the window after scroll. */
    @Test
    fun timingDiagram_fitsItsColumn() {
        val diagramMatcher = hasTestTag(PROFILE_TIMING_DIAGRAM_TAG)
        val scrollTargets = listOf(ScrollTarget("diagram", diagramMatcher))

        for (mode in modeCases) {
            for (size in DuoSize.entries) {
                checkScreenLayout(
                    screen = "profile-${mode.label}-diagram-check",
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
                        if (inspectedSize != DuoSize.OUTER_LANDSCAPE) {
                            assertTrue(
                                diagramBounds.top >= 0f,
                                "${mode.label} (${inspectedSize.label}): diagram top ${diagramBounds.top} px is above the window",
                            )
                            assertTrue(
                                diagramBounds.bottom <= windowHeightPx,
                                "${mode.label} (${inspectedSize.label}): diagram bottom ${diagramBounds.bottom} px exceeds window height $windowHeightPx px",
                            )
                        }
                    },
                ) {
                    SampleProfile(mode)
                }
            }
        }
    }

    /**
     * `ProfileTimingDiagram` has a fixed height of 360 dp. On the outer display in landscape, the
     * column under the title bar is shorter, so part of the diagram stays out of view. The plan
     * records this as a known defect. When a screen change corrects the defect, change this test
     * to assert that the whole diagram fits the window.
     */
    @Test
    fun timingDiagram_tallerThanVisibleColumnOnOuterLandscape_knownDefect() {
        val diagramMatcher = hasTestTag(PROFILE_TIMING_DIAGRAM_TAG)
        val scrollTargets = listOf(ScrollTarget("diagram", diagramMatcher))

        for (mode in modeCases) {
            checkScreenLayout(
                screen = "profile-${mode.label}-diagram-defect",
                size = DuoSize.OUTER_LANDSCAPE,
                scrollTargets = scrollTargets,
                inspect = { inspectedSize ->
                    val diagramBounds = onNode(diagramMatcher, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .unclippedBoundsInRoot()
                    val windowHeightPx = inspectedSize.height.value * RENDER_DENSITY
                    val fitsInWindow = diagramBounds.top >= 0f && diagramBounds.bottom <= windowHeightPx
                    assertFalse(
                        fitsInWindow,
                        "${mode.label} (${inspectedSize.label}): expected diagram [${diagramBounds.top}, ${diagramBounds.bottom}] to exceed window [0, $windowHeightPx]",
                    )
                },
            ) {
                SampleProfile(mode)
            }
        }
    }

    private sealed interface EyeExpectation {
        data class Level(
            val levelText: String,
            val resultText: String,
        ) : EyeExpectation

        data class Hint(val text: String) : EyeExpectation

        data object None : EyeExpectation
    }

    private data class EyeLayoutCase(
        val label: String,
        val mode: ProfileModeCase,
        val eyeReading: EyeReadingState?,
        val expectation: EyeExpectation,
    )

    /** Checks the live eye level, bar, result, and hints at every [DuoSize]. */
    @Test
    fun eyeValueFitsEveryDuoSize() {
        val saveLabel = runBlocking { getString(Res.string.configure_device_screen_save_button) }
        val ballLabel = runBlocking { getString(Res.string.profile_eye_result_ball) }
        val emptyLabel = runBlocking { getString(Res.string.profile_eye_result_empty) }
        val hintEyeOff = runBlocking { getString(Res.string.profile_eye_hint_eye_off) }
        val hintOverrideOff = runBlocking { getString(Res.string.profile_eye_hint_override_off) }
        val hintFault = runBlocking { getString(Res.string.profile_eye_hint_fault) }
        val hintUnsupported = runBlocking { getString(Res.string.profile_eye_hint_unsupported) }
        val hintNotPermitted = runBlocking { getString(Res.string.profile_eye_hint_not_permitted) }

        val breakBeamProfile = profiles.first().copy(
            eyeMode = MarkerEyeMode.BREAK_BEAM,
            eyeThreshold = 50,
        )
        val breakBeamMode = ProfileModeCase(
            label = "eye-break-beam",
            profile = breakBeamProfile,
            boltMode = MarkerBoltMode.CLOSED,
        )
        val reflectiveMode = ProfileModeCase(
            label = "eye-reflective",
            profile = breakBeamProfile.copy(eyeMode = MarkerEyeMode.REFLECTIVE),
            boltMode = MarkerBoltMode.CLOSED,
        )

        val cases = listOf(
            EyeLayoutCase(
                label = "ball",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(1500, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
                ),
                expectation = EyeExpectation.Level(
                    levelText = formatEyeLevelPercent(1500 * 100.0 / EYE_ADC_FULL_SCALE_RAW),
                    resultText = ballLabel,
                ),
            ),
            EyeLayoutCase(
                label = "empty",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(3000, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
                ),
                expectation = EyeExpectation.Level(
                    levelText = formatEyeLevelPercent(3000 * 100.0 / EYE_ADC_FULL_SCALE_RAW),
                    resultText = emptyLabel,
                ),
            ),
            EyeLayoutCase(
                label = "reflective",
                mode = reflectiveMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(3000, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
                ),
                expectation = EyeExpectation.Level(
                    levelText = formatEyeLevelPercent(3000 * 100.0 / EYE_ADC_FULL_SCALE_RAW),
                    resultText = ballLabel,
                ),
            ),
            EyeLayoutCase(
                label = "eye-off",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(null, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.EYE_OFF),
                ),
                expectation = EyeExpectation.Hint(hintEyeOff),
            ),
            EyeLayoutCase(
                label = "override-off",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(null, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.OVERRIDE_OFF),
                ),
                expectation = EyeExpectation.Hint(hintOverrideOff),
            ),
            EyeLayoutCase(
                label = "fault",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(null, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.FAULT),
                ),
                expectation = EyeExpectation.Hint(hintFault),
            ),
            EyeLayoutCase(
                label = "unsupported",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Unsupported,
                expectation = EyeExpectation.Hint(hintUnsupported),
            ),
            EyeLayoutCase(
                label = "not-permitted",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.NotPermitted,
                expectation = EyeExpectation.Hint(hintNotPermitted),
            ),
            EyeLayoutCase(
                label = "waiting",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Waiting,
                expectation = EyeExpectation.None,
            ),
            EyeLayoutCase(
                label = "active-no-level",
                mode = breakBeamMode,
                eyeReading = EyeReadingState.Reading(
                    EyeReading(null, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
                ),
                expectation = EyeExpectation.None,
            ),
            EyeLayoutCase(
                label = "none",
                mode = breakBeamMode,
                eyeReading = null,
                expectation = EyeExpectation.None,
            ),
        )

        for (eyeCase in cases) {
            val scrollTargets = when (eyeCase.expectation) {
                is EyeExpectation.Level -> listOf(ScrollTarget("eye-bar", hasTestTag(EYE_LEVEL_BAR_TAG)))
                is EyeExpectation.Hint -> listOf(ScrollTarget("eye-hint", hasTestTag(EYE_HINT_TAG)))
                EyeExpectation.None -> listOf(ScrollTarget("save", hasText(saveLabel)))
            }
            for (size in DuoSize.entries) {
                checkScreenLayout(
                    screen = "profile-eye-${eyeCase.label}",
                    size = size,
                    scrollTargets = scrollTargets,
                    inspect = { inspectedSize ->
                        val maxRightPx = inspectedSize.width.value * RENDER_DENSITY + BOUNDS_TOLERANCE_PX
                        val levelNodes = onAllNodes(hasTestTag(EYE_LIVE_LEVEL_TAG), useUnmergedTree = true)
                            .fetchSemanticsNodes()
                        val barNodes = onAllNodes(hasTestTag(EYE_LEVEL_BAR_TAG), useUnmergedTree = true)
                            .fetchSemanticsNodes()
                        val resultNodes = onAllNodes(hasTestTag(EYE_RESULT_TAG), useUnmergedTree = true)
                            .fetchSemanticsNodes()
                        val hintNodes = onAllNodes(hasTestTag(EYE_HINT_TAG), useUnmergedTree = true)
                            .fetchSemanticsNodes()
                        val caseLabel = "${eyeCase.label} (${inspectedSize.label})"

                        when (val expectation = eyeCase.expectation) {
                            is EyeExpectation.Level -> {
                                assertEquals(1, levelNodes.size, "$caseLabel: expected one level node")
                                assertEquals(1, barNodes.size, "$caseLabel: expected one bar node")
                                assertEquals(1, resultNodes.size, "$caseLabel: expected one result node")
                                assertEquals(0, hintNodes.size, "$caseLabel: expected no hint node")

                                val levelNode = levelNodes.single()
                                val barNode = barNodes.single()
                                val resultNode = resultNodes.single()

                                assertNull(
                                    levelNode.config.getOrNull(SemanticsProperties.LiveRegion),
                                    "$caseLabel: level node must not be a live region",
                                )
                                assertTrue(
                                    nodeText(levelNode).contains(expectation.levelText),
                                    "$caseLabel: level text \"${nodeText(levelNode)}\" does not hold ${expectation.levelText}",
                                )
                                val barDescription = barNode.config
                                    .getOrNull(SemanticsProperties.ContentDescription)
                                    ?.joinToString(" ")
                                    .orEmpty()
                                assertTrue(
                                    barDescription.contains(expectation.levelText) && barDescription.contains("50"),
                                    "$caseLabel: bar description \"$barDescription\" must hold ${expectation.levelText} and 50",
                                )
                                assertEquals(
                                    expectation.resultText,
                                    nodeText(resultNode),
                                    "$caseLabel: result text mismatch",
                                )
                                assertEquals(
                                    LiveRegionMode.Polite,
                                    resultNode.config.getOrNull(SemanticsProperties.LiveRegion),
                                    "$caseLabel: result node must be a polite live region",
                                )
                                assertNodeFitsWidth(levelNode, maxRightPx, "$caseLabel level")
                                assertNodeFitsWidth(barNode, maxRightPx, "$caseLabel bar")
                                assertNodeFitsWidth(resultNode, maxRightPx, "$caseLabel result")
                            }

                            is EyeExpectation.Hint -> {
                                assertEquals(1, hintNodes.size, "$caseLabel: expected one hint node")
                                assertEquals(0, levelNodes.size, "$caseLabel: expected no level node")
                                assertEquals(0, barNodes.size, "$caseLabel: expected no bar node")
                                assertEquals(0, resultNodes.size, "$caseLabel: expected no result node")

                                val hintNode = hintNodes.single()
                                assertEquals(
                                    expectation.text,
                                    nodeText(hintNode),
                                    "$caseLabel: hint text mismatch",
                                )
                                assertNodeFitsWidth(hintNode, maxRightPx, "$caseLabel hint")
                            }

                            EyeExpectation.None -> {
                                assertEquals(0, levelNodes.size, "$caseLabel: expected no level node")
                                assertEquals(0, barNodes.size, "$caseLabel: expected no bar node")
                                assertEquals(0, resultNodes.size, "$caseLabel: expected no result node")
                                assertEquals(0, hintNodes.size, "$caseLabel: expected no hint node")
                            }
                        }
                    },
                ) {
                    SampleProfile(eyeCase.mode, eyeReading = eyeCase.eyeReading)
                }
            }
        }
    }

    private fun nodeText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            .orEmpty()

    private fun assertNodeFitsWidth(node: SemanticsNode, maxRightPx: Float, label: String) {
        val rightPx = node.unclippedBoundsInRoot().right
        assertTrue(
            rightPx <= maxRightPx,
            "$label: right $rightPx px exceeds window width $maxRightPx px",
        )
    }

    @Composable
    private fun SampleProfile(
        mode: ProfileModeCase,
        eyeReading: EyeReadingState? = null,
    ) {
        val found = ProfileLoad.Found(
            profile = mode.profile,
            boardProfile = mode.profile,
            boltMode = mode.boltMode,
        )
        ProfileContent(
            profile = mode.profile,
            profileLoad = found,
            boardProfile = mode.profile,
            boltMode = mode.boltMode,
            eyeReading = eyeReading,
            photoPath = "",
            onProfileChange = {},
            onCancel = {},
            onSave = {},
            onBack = {},
        )
    }

    private companion object {
        const val MAX_DIAGRAM_HORIZONTAL_PADDING_DP = 64f
    }
}
