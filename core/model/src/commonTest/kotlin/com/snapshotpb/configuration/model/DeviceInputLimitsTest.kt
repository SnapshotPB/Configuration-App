package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceInputLimitsTest {

    // --- limitDeviceName ---

    @Test
    fun limitDeviceName_truncatesToMaxLength() {
        assertEquals("0123456789", "0123456789abcdef".limitDeviceName())
    }

    @Test
    fun limitDeviceName_leavesShorterValuesUnchanged() {
        assertEquals("Marker", "Marker".limitDeviceName())
        assertEquals("", "".limitDeviceName())
    }

    @Test
    fun limitDeviceName_keepsExactlyMaxLength() {
        val exact = "X".repeat(Device.MAX_NAME_LENGTH)
        assertEquals(exact, exact.limitDeviceName())
    }

    @Test
    fun limitDeviceName_allowsNonAsciiCharacters() {
        // The device name is local-only (not in the board protocol), so it is not filtered.
        assertEquals("café Köln", "café Köln".limitDeviceName())
    }

    // --- newDeviceName ---

    @Test
    fun newDeviceName_replacesAnAdvertisedNameThatDoesNotFit() {
        // The board advertises "Snapshot PB", which has 11 characters.
        assertEquals("SnapshotPB", newDeviceName("Snapshot PB", defaultName = "SnapshotPB"))
    }

    @Test
    fun newDeviceName_keepsAnAdvertisedNameThatFits() {
        assertEquals("Marker", newDeviceName("Marker", defaultName = "SnapshotPB"))
        val exact = "X".repeat(Device.MAX_NAME_LENGTH)
        assertEquals(exact, newDeviceName(exact, defaultName = "SnapshotPB"))
    }

    @Test
    fun newDeviceName_replacesABlankAdvertisedName() {
        assertEquals("SnapshotPB", newDeviceName("", defaultName = "SnapshotPB"))
        assertEquals("SnapshotPB", newDeviceName("   ", defaultName = "SnapshotPB"))
    }

    @Test
    fun newDeviceName_cutsADefaultNameThatDoesNotFit() {
        // A translation of the default name can be too long. The stored name must still fit.
        assertEquals("0123456789", newDeviceName("Snapshot PB", defaultName = "0123456789abc"))
    }

    // --- limitBootText ---

    @Test
    fun limitBootText_truncatesToMaxLength() {
        assertEquals("SnapshotPB", "SnapshotPB Marker".limitBootText())
    }

    @Test
    fun limitBootText_keepsExactlyMaxLength() {
        val exact = "A".repeat(Device.MAX_BOOT_TEXT_LENGTH)
        assertEquals(exact, exact.limitBootText())
    }

    @Test
    fun limitBootText_handlesEmptyValue() {
        assertEquals("", "".limitBootText())
    }

    @Test
    fun limitBootText_filtersOutNonAsciiCharacters() {
        // Non-ASCII (code point > 127) is dropped so 1 character is always 1 UTF-8 byte,
        // keeping the character limit in agreement with the board's 10-byte field.
        assertEquals("caf", "café".limitBootText())
        assertEquals("ABC", "A名B前C".limitBootText())
    }

    @Test
    fun limitBootText_respectsAsciiBoundary() {
        // Code point 127 is the last kept value; 128 and 255 are dropped.
        assertEquals(Char(127).toString(), Char(127).toString().limitBootText())
        assertEquals("", Char(128).toString().limitBootText())
        assertEquals("", Char(255).toString().limitBootText())
    }

    @Test
    fun limitBootText_filtersBeforeApplyingLengthCap() {
        // Filtering happens first, so dropped characters do not consume the length budget.
        assertEquals("ABCDEFGHIJ", "AB名CDEFGHIJKL".limitBootText())
    }

    // --- limitProfileName ---

    @Test
    fun limitProfileName_truncatesToTenCharacters() {
        assertEquals("0123456789", "0123456789abcdef".limitProfileName())
    }

    @Test
    fun limitProfileName_keepsExactlyMaxLength() {
        val exact = "X".repeat(Device.MAX_PROFILE_NAME_LENGTH)
        assertEquals(exact, exact.limitProfileName())
        assertEquals(10, Device.MAX_PROFILE_NAME_LENGTH)
    }

    @Test
    fun limitProfileName_filtersNonAsciiThenCaps() {
        // Non-ASCII is dropped so 10 characters is always exactly 10 wire bytes; filtering happens
        // before the cap so dropped characters do not consume the budget.
        assertEquals("cafKln", "caféKöln".limitProfileName())
        assertEquals("ABCDEFGHIJ", "AB名CDEFGHIJKL".limitProfileName())
    }

    @Test
    fun limitProfileName_isIdempotent() {
        val once = "café-Marker-12345".limitProfileName()
        assertEquals(once, once.limitProfileName())
    }
}