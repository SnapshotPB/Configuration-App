package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.common.composable.finishedDraftValue
import com.snapshotpb.configuration.common.composable.immediateDraftValue
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.RATE_CAP_MIN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cover for the text input rules of the phone profile editor: the limits come from
 * [MarkerProfileField], and a partial text stays a draft until it is a value the board keeps.
 */
class ProfileFieldInputTest {

    @Test
    fun typedValue_holdsTheValueInsideTheTableLimits() {
        MarkerProfileField.entries.forEach { field ->
            assertEquals(field.min, field.typedValue(field.min - 1), "$field below its min")
            assertEquals(field.max, field.typedValue(field.max + 1), "$field above its max")
        }
    }

    @Test
    fun typedValue_raisesAFireRateCapInTheGapToTheLowestCap() {
        val field = MarkerProfileField.FIRE_RATE_CAP
        assertEquals(0, field.typedValue(0), "0 is uncapped and stays")
        assertEquals(RATE_CAP_MIN, field.typedValue(1))
        assertEquals(RATE_CAP_MIN, field.typedValue(RATE_CAP_MIN - 1))
        assertEquals(RATE_CAP_MIN + 1, field.typedValue(RATE_CAP_MIN + 1))
    }

    @Test
    fun formatAndParse_followTheUnitOfTheField() {
        assertTrue(MarkerProfileField.SEAR_SOLENOID_DWELL.isTenths)
        assertTrue(MarkerProfileField.FIRE_RATE_CAP.isTenths)
        assertFalse(MarkerProfileField.EYE_THRESHOLD.isTenths)
        assertFalse(MarkerProfileField.RAMPING_CUSTOM_TRIGGER_PULLS.isTenths)

        assertEquals("10.5", MarkerProfileField.SEAR_SOLENOID_DWELL.formatInput(105))
        assertEquals(105, MarkerProfileField.SEAR_SOLENOID_DWELL.parseInput("10.5"))
        assertEquals("50", MarkerProfileField.EYE_THRESHOLD.formatInput(50))
        assertEquals(50, MarkerProfileField.EYE_THRESHOLD.parseInput(" 50 "))
        assertNull(MarkerProfileField.EYE_THRESHOLD.parseInput(""))
        assertNull(MarkerProfileField.EYE_THRESHOLD.parseInput("5.5"))
    }

    @Test
    fun rampingMultiplier_showsAsAMultipleWithOneDecimal() {
        // The board stores the percent divided by ten: 25 is 250 percent, which is 2.5 times.
        val field = MarkerProfileField.RAMPING_CUSTOM_MULTIPLIER
        assertTrue(field.isTenths)
        assertEquals("2.5", field.formatInput(25))
        assertEquals(25, field.parseInput("2.5"))
        assertEquals(30, field.parseInput("3"))
    }

    @Test
    fun rampingRofMinimum_staysAWholeNumber() {
        val field = MarkerProfileField.RAMPING_CUSTOM_ROF_MINIMUM
        assertFalse(field.isTenths)
        assertEquals("3", field.formatInput(3))
        assertNull(field.parseInput("3.5"))
    }

    @Test
    fun immediateDraftValue_keepsPartialAndOutOfRangeTextAsADraft() {
        val field = MarkerProfileField.EYE_THRESHOLD
        // A cleared field, and a 0 that the table does not allow, commit nothing yet.
        assertNull(immediateDraftValue("", field::parseInput, field::typedValue))
        assertNull(immediateDraftValue("0", field::parseInput, field::typedValue))
        assertEquals(7, immediateDraftValue("7", field::parseInput, field::typedValue))

        // "10." parses to the value on the way to "10.5", so it commits and stays in the field.
        val dwell = MarkerProfileField.SEAR_SOLENOID_DWELL
        assertEquals(100, immediateDraftValue("10.", dwell::parseInput, dwell::typedValue))
    }

    @Test
    fun finishedDraftValue_normalizesTheTextOrRestoresTheValue() {
        val field = MarkerProfileField.EYE_THRESHOLD
        assertEquals(1, finishedDraftValue("0", 50, field::parseInput, field::typedValue))
        assertEquals(50, finishedDraftValue("", 50, field::parseInput, field::typedValue))
        assertEquals(field.max, finishedDraftValue("999", 50, field::parseInput, field::typedValue))

        val cap = MarkerProfileField.FIRE_RATE_CAP
        assertEquals(RATE_CAP_MIN, finishedDraftValue("0.5", 0, cap::parseInput, cap::typedValue))
    }
}
