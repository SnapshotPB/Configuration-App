package com.snapshotpb.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals

class OptInTest {

    @Test
    fun `the default opt-ins include the serialization marker`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
            ),
            optInValues(serialization = true),
        )
    }

    @Test
    fun `a module without serialization gets only the stdlib markers`() {
        assertEquals(
            listOf(
                "kotlin.ExperimentalUnsignedTypes",
                "kotlin.uuid.ExperimentalUuidApi",
            ),
            optInValues(serialization = false),
        )
    }
}
