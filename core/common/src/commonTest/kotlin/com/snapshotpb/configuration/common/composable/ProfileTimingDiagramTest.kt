package com.snapshotpb.configuration.common.composable

import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun profile(
        eyeMode: MarkerEyeMode,
        fireMode: MarkerFireMode,
    ) = MarkerProfile(
        id = id,
        name = "P",
        fireMode = fireMode,
        eyeMode = eyeMode,
        eyeDebounce = 20,
        searSolenoidDwell = 45,
        searSolenoidDelay = 60,
        cockingSolenoidDelay = 60,
        cockingSolenoidDwellOpen = 50,
        cockingSolenoidDwellClosed = 300,
    )

    @Test
    fun drawnCycle_isTheCycleOfTheMaxBps_forEveryEyeModeFireModeAndBoltMode() {
        MarkerEyeMode.entries.forEach { eyeMode ->
            MarkerFireMode.entries.forEach { fireMode ->
                MarkerBoltMode.entries.forEach { boltMode ->
                    val profile = profile(eyeMode, fireMode)
                    val phases = timingDiagramPhases(profile, boltMode)
                    val context = "$eyeMode $fireMode $boltMode"
                    assertEquals(profile.usesOpenBolt(boltMode), phases.isOpenBolt, context)
                    assertEquals(profile.cockingOpenTenthsMs.toFloat(), phases.con, context)
                    assertEquals(profile.cycleTenthsMs(boltMode).toFloat(), phases.cycle, context)
                }
            }
        }
    }

    @Test
    fun openBolt_whenCockingExceedsSear_cycleIsConPlusCoff() {
        val openBolt = profile(MarkerEyeMode.OFF, MarkerFireMode.SEMI).copy(
            cockingSolenoidDwellOpen = 50,
            cockingSolenoidDwellClosed = 300,
            searSolenoidDelay = 60,
            searSolenoidDwell = 45,
        )
        val phases = timingDiagramPhases(openBolt, MarkerBoltMode.OPEN)
        assertTrue(phases.isOpenBolt)
        assertEquals(60f, phases.sdel)
        assertEquals(45f, phases.son)
        assertEquals(50f, phases.con)
        assertEquals(300f, phases.coff)
        assertEquals(350f, phases.cycle)
        assertEquals(openBolt.cycleTenthsMs(MarkerBoltMode.OPEN).toFloat(), phases.cycle)
    }

    @Test
    fun openBolt_whenSearExceedsCocking_cycleIsSdelPlusSon() {
        val openBolt = profile(MarkerEyeMode.OFF, MarkerFireMode.SEMI).copy(
            cockingSolenoidDwellOpen = 50,
            cockingSolenoidDwellClosed = 100,
            searSolenoidDelay = 250,
            searSolenoidDwell = 200,
        )
        val phases = timingDiagramPhases(openBolt, MarkerBoltMode.OPEN)
        assertTrue(phases.isOpenBolt)
        assertEquals(250f, phases.sdel)
        assertEquals(200f, phases.son)
        assertEquals(450f, phases.cycle)
        assertEquals(openBolt.cycleTenthsMs(MarkerBoltMode.OPEN).toFloat(), phases.cycle)
    }

    @Test
    fun openBoltStoredInMechanicalOrTriggerOnly_usesClosedBoltDiagram() {
        listOf(
            MarkerFireMode.MECHANICAL,
            MarkerFireMode.TRIGGER_ONLY,
            MarkerFireMode.UNKNOWN,
        ).forEach { fireMode ->
            val p = profile(MarkerEyeMode.OFF, fireMode).copy(
                cockingSolenoidDelay = 60,
                cockingSolenoidDwellOpen = 50,
                cockingSolenoidDwellClosed = 300,
                searSolenoidDelay = 500,
                searSolenoidDwell = 500,
            )
            val phases = timingDiagramPhases(p, MarkerBoltMode.OPEN)
            assertFalse(phases.isOpenBolt, "$fireMode")
            // Closed-bolt cycle is cdel + con + coff = 60 + 50 + 300 = 410, and son is clamped to 410.
            assertEquals(410f, phases.cycle, "$fireMode")
            assertEquals(410f, phases.son, "$fireMode")
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
        val zeroClosed = profile(MarkerEyeMode.OFF, MarkerFireMode.SEMI).copy(
            searSolenoidDelay = 0,
            searSolenoidDwell = 0,
            cockingSolenoidDelay = 0,
            cockingSolenoidDwellOpen = 0,
            cockingSolenoidDwellClosed = 0,
        )
        val closedPhases = timingDiagramPhases(zeroClosed, MarkerBoltMode.CLOSED)
        assertEquals(1f, closedPhases.sdel)
        assertEquals(1f, closedPhases.son)
        assertEquals(1f, closedPhases.cdel)
        assertEquals(1f, closedPhases.con)
        assertEquals(1f, closedPhases.coff)

        val openPhases = timingDiagramPhases(zeroClosed, MarkerBoltMode.OPEN)
        assertEquals(1f, openPhases.sdel)
        assertEquals(1f, openPhases.son)
        assertEquals(1f, openPhases.con)
        assertEquals(1f, openPhases.coff)
        assertEquals(2f, openPhases.cycle)
    }
}
