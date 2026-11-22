package com.snapshotpb.configuration.bluetooth.eye

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.EyeStatus
import snapshotpb.v1.EyeSensorState as ProtoEyeSensorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks [decodeEyeStatus] and [EyeStatus] against the golden file `eye_status.json` in the
 * Configuration-Protocol submodule.
 */
class EyeStatusGoldenTest {

    private data class GoldenFile(
        val message: String,
        val cases: List<GoldenCase>,
    )

    private data class GoldenCase(
        val name: String,
        val proto: EyeStatus,
        val hex: String,
    )

    private fun loadGoldenFile(): GoldenFile {
        val stream = assertNotNull(
            javaClass.getResourceAsStream(GOLDEN_PATH),
            "The golden file $GOLDEN_PATH is not on the test classpath",
        )
        val text = stream.bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        val message = root.getValue("message").jsonPrimitive.content
        val cases = root.getValue("cases").jsonArray.map { element ->
            val entry = element.jsonObject
            val fields = entry.getValue("fields").jsonObject
            val levelRaw = fields["level_raw"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.toUInt()
            val fullScaleRaw = fields["full_scale_raw"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.toUInt()
                ?: 0U
            val state = fields["state"]
                ?.takeUnless { it is JsonNull }
                ?.jsonPrimitive
                ?.content
                ?.let { ProtoEyeSensorState.valueOf(it) }
                ?: ProtoEyeSensorState.EYE_SENSOR_STATE_UNSPECIFIED
            GoldenCase(
                name = entry.getValue("name").jsonPrimitive.content,
                proto = EyeStatus(
                    levelRaw = levelRaw,
                    fullScaleRaw = fullScaleRaw,
                    state = state,
                ),
                hex = entry.getValue("hex").jsonPrimitive.content,
            )
        }
        return GoldenFile(message = message, cases = cases)
    }

    @Test
    fun goldenFileHasExpectedMessageAndCaseCount() {
        val golden = loadGoldenFile()
        assertEquals(EXPECTED_MESSAGE, golden.message, "The message name in $GOLDEN_PATH differs")
        assertTrue(
            golden.cases.size >= MINIMUM_CASE_COUNT,
            "The golden file $GOLDEN_PATH must hold at least $MINIMUM_CASE_COUNT cases, got ${golden.cases.size}",
        )
    }

    @Test
    fun everyGoldenCaseDecodesAndEncodes() {
        val golden = loadGoldenFile()
        golden.cases.forEach { case ->
            val bytes = case.hex.hexToByteArray()
            val expectedReading = case.proto.toEyeReading()
            assertEquals(
                expectedReading,
                decodeEyeStatus(bytes),
                "decodeEyeStatus differs for case ${case.name}",
            )

            val encodedHex = ProtoBuf.encodeToByteArray(EyeStatus.serializer(), case.proto).toHexString()
            assertEquals(
                case.hex,
                encodedHex,
                "ProtoBuf encode differs for case ${case.name}",
            )
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/eye_status.json"
        const val EXPECTED_MESSAGE = "snapshotpb.v1.EyeStatus"
        const val MINIMUM_CASE_COUNT = 7
    }
}
