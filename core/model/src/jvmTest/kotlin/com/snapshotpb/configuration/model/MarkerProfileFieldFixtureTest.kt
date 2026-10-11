package com.snapshotpb.configuration.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compares [MarkerProfileField] with the committed fixture `profile-fields.json`.
 *
 * The Zepp app keeps a hand copy of the table, and a Zepp test can compare that copy with the same
 * fixture. This test holds the Kotlin side: a change to the table that the fixture does not show
 * fails here, so the fixture, and each copy checked against it, gets the same change.
 *
 * This fixture holds the limits of the numeric fields only. The fixture `marker-profile-fields.json`
 * holds every [MarkerProfile] field and its default, and [MarkerProfileFixtureTest] compares it.
 */
class MarkerProfileFieldFixtureTest {

    private data class FixtureField(
        val key: String,
        val unit: String,
        val min: Int,
        val max: Int,
        val group: String,
        val affectsMaxBps: Boolean,
    )

    private fun loadFixture(): List<FixtureField> {
        val stream = assertNotNull(
            javaClass.getResourceAsStream(FIXTURE_PATH),
            "The fixture $FIXTURE_PATH is not on the test classpath",
        )
        val text = stream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonArray.map { element ->
            val entry = element.jsonObject
            FixtureField(
                key = entry.getValue("key").jsonPrimitive.content,
                unit = entry.getValue("unit").jsonPrimitive.content,
                min = entry.getValue("min").jsonPrimitive.int,
                max = entry.getValue("max").jsonPrimitive.int,
                group = entry.getValue("group").jsonPrimitive.content,
                affectsMaxBps = entry.getValue("affectsMaxBps").jsonPrimitive.boolean,
            )
        }
    }

    private fun MarkerProfileField.toFixtureField() = FixtureField(
        key = name,
        unit = unit.name,
        min = min,
        max = max,
        group = group.name,
        affectsMaxBps = affectsMaxBps,
    )

    @Test
    fun fieldKeysMatchFixtureInOrder() {
        assertEquals(
            loadFixture().map { it.key },
            MarkerProfileField.entries.map { it.name },
        )
    }

    @Test
    fun everyFieldMatchesItsFixtureEntry() {
        val fixture = loadFixture()
        assertEquals(fixture.size, MarkerProfileField.entries.size, "The number of fields differs")
        MarkerProfileField.entries.zip(fixture).forEach { (field, expected) ->
            assertEquals(expected, field.toFixtureField(), "Field ${field.name} differs from the fixture")
        }
    }

    private companion object {
        const val FIXTURE_PATH = "/profile-fields.json"
    }
}
