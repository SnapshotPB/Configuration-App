package com.snapshotpb.configuration.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

/**
 * Compares the stored fields of [MarkerProfile] with the committed fixture `marker-profile-fields.json`.
 *
 * The Zepp app keeps a hand copy of the profile fields and their defaults. It uses that copy to
 * find a board change and to fill a field that a stored profile does not have. A Zepp test
 * compares the copy with the same fixture. This test holds the Kotlin side: a new, removed or
 * renamed [MarkerProfile] field, or a changed default, fails here. Then the fixture, and the Zepp
 * copy that is checked against it, get the same change.
 *
 * The names are the serial names, because the Zepp app stores a profile under those keys. A new
 * field with no default also stops the compile of [everyDefaultMatchesFixture], because that test
 * makes a profile from the required fields only.
 *
 * This fixture holds every profile field. The fixture `profile-fields.json` holds the limits of
 * the numeric fields only, and [MarkerProfileFieldFixtureTest] compares it with [MarkerProfileField].
 *
 * The fixture puts the defaulted fields in two groups. `defaults` holds the profile settings, which
 * the Zepp app stores and compares. `appOnly` holds the fields that only this app keeps, for
 * example [MarkerProfile.sourceSlot]. The user cannot see or change them, and the divergence
 * checks ignore them ([MarkerProfile.equalsIgnoringSourceSlot]), so the Zepp lists do not hold
 * them.
 */
class MarkerProfileFixtureTest {

    private fun loadFixture(): JsonObject {
        val stream = assertNotNull(
            javaClass.getResourceAsStream(FIXTURE_PATH),
            "The fixture $FIXTURE_PATH is not on the test classpath",
        )
        val text = stream.bufferedReader().use { it.readText() }
        return Json.parseToJsonElement(text).jsonObject
    }

    private val descriptor = MarkerProfile.serializer().descriptor

    private fun elementNames(optional: Boolean): List<String> =
        (0 until descriptor.elementsCount)
            .filter { descriptor.isElementOptional(it) == optional }
            .map { descriptor.getElementName(it) }

    @Test
    fun requiredFieldsMatchFixtureInOrder() {
        assertEquals(
            loadFixture().getValue("required").jsonArray.map { it.jsonPrimitive.content },
            elementNames(optional = false),
        )
    }

    @Test
    fun defaultedFieldsMatchFixtureInOrder() {
        val fixture = loadFixture()
        assertEquals(
            fixture.getValue("defaults").jsonObject.keys.toList() +
                fixture.getValue("appOnly").jsonObject.keys.toList(),
            elementNames(optional = true),
        )
    }

    @Test
    fun everyDefaultMatchesFixture() {
        val fixture = loadFixture()
        val defaults = JsonObject(
            fixture.getValue("defaults").jsonObject + fixture.getValue("appOnly").jsonObject,
        )
        val encoded = encodeDefaultsJson
            .encodeToJsonElement(MarkerProfile.serializer(), MarkerProfile(id = Uuid.NIL, name = ""))
            .jsonObject
        // One compare of the two maps shows every default that differs, not only the first one.
        assertEquals(defaults, JsonObject(encoded.filterKeys { it in defaults }))
    }

    private companion object {
        const val FIXTURE_PATH = "/marker-profile-fields.json"

        val encodeDefaultsJson = Json { encodeDefaults = true }
    }
}
