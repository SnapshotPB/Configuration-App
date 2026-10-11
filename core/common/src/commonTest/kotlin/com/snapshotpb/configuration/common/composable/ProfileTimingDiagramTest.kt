package com.snapshotpb.configuration.common.composable

import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The sizes are in pixels on a canvas that is 300 px wide. A label is centred on its segment, so
 * its start is the middle of the segment minus half of the label width.
 */
class ProfileTimingDiagramTest {

    @Test
    fun labelInsideTheCanvas_doesNotMove() {
        assertEquals(40f, boundedLabelStart(start = 40f, length = 60f, limit = 300f))
    }

    @Test
    fun labelOnTheEdges_doesNotMove() {
        assertEquals(0f, boundedLabelStart(start = 0f, length = 60f, limit = 300f))
        assertEquals(240f, boundedLabelStart(start = 240f, length = 60f, limit = 300f))
    }

    @Test
    fun labelAcrossTheLeftEdge_startsAtTheLeftEdge() {
        // "Debounce On" is 80 px wide and centred on a 2.0 ms press debounce at the start of a
        // 43 ms axis, so the middle of its segment is at 7 px.
        assertEquals(0f, boundedLabelStart(start = -33f, length = 80f, limit = 300f))
    }

    @Test
    fun labelAcrossTheRightEdge_endsAtTheRightEdge() {
        // "Off" is 30 px wide and centred on a short COFF segment at the end of the axis.
        assertEquals(270f, boundedLabelStart(start = 285f, length = 30f, limit = 300f))
    }

    @Test
    fun labelWiderThanTheCanvas_startsAtTheLeftEdge() {
        assertEquals(0f, boundedLabelStart(start = -20f, length = 400f, limit = 300f))
        assertEquals(0f, boundedLabelStart(start = 50f, length = 400f, limit = 300f))
    }

    // --- The drawn cycle is the cycle of the max BPS, for each eye mode ---

    private val id = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun profile(eyeMode: MarkerEyeMode, fireMode: MarkerFireMode) = MarkerProfile(
        id = id,
        name = "P",
        fireMode = fireMode,
        eyeMode = eyeMode,
        eyeDebounce = 20,
        cockingSolenoidDelay = 60,
        cockingSolenoidDwellOpen = 50,
        cockingSolenoidDwellClosed = 300,
    )

    @Test
    fun drawnCycle_isTheCycleOfTheMaxBps_forEveryEyeModeAndFireMode() {
        MarkerEyeMode.entries.forEach { eyeMode ->
            MarkerFireMode.entries.forEach { fireMode ->
                val profile = profile(eyeMode, fireMode)
                val phases = timingDiagramPhases(profile)
                assertEquals(profile.cockingOpenTenthsMs.toFloat(), phases.con, "$eyeMode $fireMode")
                assertEquals(profile.cycleTenthsMs.toFloat(), phases.cycle, "$eyeMode $fireMode")
            }
        }
    }

    @Test
    fun eyeOff_drawsTheDwellOpen() {
        assertEquals(50f, timingDiagramPhases(profile(MarkerEyeMode.OFF, MarkerFireMode.SEMI)).con)
    }

    @Test
    fun eyeOn_drawsTheEyeDebounce_notTheDwellOpen() {
        listOf(MarkerEyeMode.REFLECTIVE, MarkerEyeMode.BREAK_BEAM).forEach { eyeMode ->
            assertEquals(20f, timingDiagramPhases(profile(eyeMode, MarkerFireMode.SEMI)).con, "$eyeMode")
        }
    }

    @Test
    fun theCycleStartsWhenTheDebounceOnEnds() {
        // The firmware starts the sear and the cocking delay when TRIGGER_PRESSED is posted, at the
        // end of the press debounce, so no phase of the cycle shares a start with the trigger edge.
        val phases = timingDiagramPhases(profile(MarkerEyeMode.REFLECTIVE, MarkerFireMode.SEMI))
        assertEquals(phases.tOn, phases.cycleStart)
        assertTrue(phases.cycleStart > 0f)
    }

    @Test
    fun zeroSettings_keepAVisibleSegment() {
        val zero = profile(MarkerEyeMode.OFF, MarkerFireMode.SEMI).copy(
            cockingSolenoidDelay = 0,
            cockingSolenoidDwellOpen = 0,
            cockingSolenoidDwellClosed = 0,
        )
        val phases = timingDiagramPhases(zero)
        assertEquals(1f, phases.cdel)
        assertEquals(1f, phases.con)
        assertEquals(1f, phases.coff)
    }
}
