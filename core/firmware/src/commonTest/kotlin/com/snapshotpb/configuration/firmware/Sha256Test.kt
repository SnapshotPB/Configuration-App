package com.snapshotpb.configuration.firmware

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    @Test
    fun givesTheKnownHashes() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun givesTheHashOfTheSiteForAFixtureImage() {
        // The image of v2026.52.1 in the fixtures of the firmware site generator.
        assertEquals(
            "f54293faeea163ee474b21c5ae7188bafd1fff82243d2f8e10fafa12f7749bef",
            sha256Hex("fake update image of snapshot_v2 2026.52.1\n".encodeToByteArray()),
        )
    }
}
