package com.snapshotpb.configuration.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compares [eyeThresholdRaw] and [eyeSeesBall] with the committed fixture `eye-ball-rule.json`.
 *
 * The Zepp app keeps a hand copy of the eye ball rule, and a Zepp test reads the same fixture.
 * This test checks the Kotlin rule against every case in the fixture and checks that the fixture
 * keeps all expected modes and thresholds.
 */
class EyeBallRuleFixtureTest {

    private data class FixtureCase(
        val eyeMode: MarkerEyeMode,
        val fullScaleRaw: Int,
        val thresholdPercent: Int,
        val levelRaw: Int,
        val thresholdRaw: Int,
        val seesBall: Boolean?,
    ) {
        val label: String
            get() = "eyeMode=$eyeMode fullScaleRaw=$fullScaleRaw thresholdPercent=$thresholdPercent levelRaw=$levelRaw"
    }

    private fun loadFixture(): List<FixtureCase> {
        val stream = assertNotNull(
            javaClass.getResourceAsStream(FIXTURE_PATH),
            "The fixture $FIXTURE_PATH is not on the test classpath",
        )
        val text = stream.bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val entry = element.jsonObject
            val seesBallElement = entry.getValue("seesBall")
            FixtureCase(
                eyeMode = MarkerEyeMode.valueOf(entry.getValue("eyeMode").jsonPrimitive.content),
                fullScaleRaw = entry.getValue("fullScaleRaw").jsonPrimitive.int,
                thresholdPercent = entry.getValue("thresholdPercent").jsonPrimitive.int,
                levelRaw = entry.getValue("levelRaw").jsonPrimitive.int,
                thresholdRaw = entry.getValue("thresholdRaw").jsonPrimitive.int,
                seesBall = if (seesBallElement is JsonNull) null else seesBallElement.jsonPrimitive.boolean,
            )
        }
    }

    @Test
    fun fixtureCoversExpectedCountModesAndThresholds() {
        val cases = loadFixture()
        assertEquals(EXPECTED_CASE_COUNT, cases.size, "The number of fixture cases differs")
        assertEquals(
            MarkerEyeMode.selectable.toSet(),
            cases.map { it.eyeMode }.toSet(),
            "The fixture must hold each selectable eye mode",
        )
        assertEquals(
            EXPECTED_THRESHOLDS,
            cases.map { it.thresholdPercent }.toSet(),
            "The fixture must hold each expected threshold percent",
        )
    }

    @Test
    fun everyFixtureCaseMatchesThresholdAndBallRule() {
        loadFixture().forEach { case ->
            assertEquals(
                case.thresholdRaw,
                eyeThresholdRaw(case.fullScaleRaw, case.thresholdPercent),
                "eyeThresholdRaw differs for ${case.label}",
            )
            assertEquals(
                case.seesBall,
                eyeSeesBall(case.eyeMode, case.levelRaw, case.fullScaleRaw, case.thresholdPercent),
                "eyeSeesBall differs for ${case.label}",
            )
        }
    }

    @Test
    fun effectiveEyeThresholdPercentAppliesDefaultAndMaximum() {
        val expectedByInput = listOf(
            -1 to 50,
            0 to 50,
            1 to 1,
            50 to 50,
            100 to 100,
            101 to 100,
        )
        expectedByInput.forEach { (input, expected) ->
            assertEquals(
                expected,
                effectiveEyeThresholdPercent(input),
                "effectiveEyeThresholdPercent differs for thresholdPercent=$input",
            )
        }
    }

    private companion object {
        const val FIXTURE_PATH = "/eye-ball-rule.json"
        const val EXPECTED_CASE_COUNT = 48
        val EXPECTED_THRESHOLDS = setOf(0, 1, 50, 100, 101)
    }
}
