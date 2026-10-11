package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser against the advertisement that the firmware sends. The bytes follow
 * `snapshot_bt_init()` in the firmware `app/src/subsystems/bluetooth/bluetooth.c`, with the values of
 * `app/Kconfig` (board type "Cocker", UUID 0xFFE0), `app/prj.conf` (name "Snapshot PB") and
 * `app/VERSION` (26.29.0, EXTRAVERSION dev).
 */
class AdvertisementDataTest {

    /** The scan response: sd[0] is the complete name, sd[1] is the 0xFFE0 service data. */
    private val scanResponse: ByteArray = byteArrayOf(
        // sd[0]: length 12, type 0x09 (complete local name), "Snapshot PB".
        12, 0x09, *"Snapshot PB".encodeToByteArray(),
        // sd[1]: length 15, type 0x16 (service data, 16-bit UUID), UUID 0xFFE0 little-endian,
        0x0F, 0x16, 0xE0.toByte(), 0xFF.toByte(),
        // board type length and "Cocker",
        6, *"Cocker".encodeToByteArray(),
        // FW_VERSION_ENC_LEN, then [year - 2000, ISO week, count, FW_FLAG_DEV].
        4, 26, 29, 0, 0x01,
    )

    /** The data of the 0xFFE0 AD structure in [scanResponse], with its UUID: what an iOS or raw read gives. */
    private fun serviceDataWithUuid(): ByteArray {
        var offset = 0
        while (offset < scanResponse.size) {
            val length = scanResponse[offset].toInt() and 0xFF
            val type = scanResponse[offset + 1].toInt() and 0xFF
            if (type == SERVICE_DATA_16 && scanResponse[offset + 2] == 0xE0.toByte()) {
                return scanResponse.copyOfRange(offset + 2, offset + 1 + length)
            }
            offset += 1 + length
        }
        error("no 0xFFE0 service data")
    }

    @Test
    fun scanResponse_fitsTheLegacyBudgetOfTheFirmware() {
        // The firmware BUILD_ASSERT keeps the scan response at 31 bytes or less.
        assertTrue(scanResponse.size <= 31, "scan response is ${scanResponse.size} bytes")
    }

    @Test
    fun parse_readsTheFourByteVersionOfTheFirmware() {
        val parsed = AdvertisementData.parse(serviceDataWithUuid())

        assertEquals("Cocker", parsed?.boardType)
        assertEquals("2026.29.0-dev", parsed?.fwVersion)
        assertEquals(FirmwareVersion(year = 2026, week = 29, count = 0, dev = true), parsed?.firmwareVersion)
        assertEquals(emptyList(), parsed?.extraFields)
    }

    @Test
    fun parse_withoutTheUuid_givesTheSameResult() {
        // Android ScanRecord.getServiceData gives the data after the UUID.
        val withUuid = serviceDataWithUuid()

        assertEquals(AdvertisementData.parse(withUuid), AdvertisementData.parse(withUuid.copyOfRange(2, withUuid.size)))
    }

    @Test
    fun parse_aReleaseBuild_hasNoDevSuffix() {
        val data = byteArrayOf(6, *"Cocker".encodeToByteArray(), 4, 26, 31, 2, 0)

        assertEquals("2026.31.2", AdvertisementData.parse(data)?.fwVersion)
    }

    @Test
    fun parse_aShortPayload_isNull() {
        assertNull(AdvertisementData.parse(byteArrayOf(6, *"Cocker".encodeToByteArray())))
        assertNull(AdvertisementData.parse(byteArrayOf(6, *"Cocker".encodeToByteArray(), 4, 26, 29)))
    }

    @Test
    fun readableFwVersion_hidesTheTextThatTheOldParserStored() {
        // The old parser decoded the 4 version bytes 26, 29, 0, 1 as text.
        val stored = AdvertisementData(boardType = "Cocker", fwVersion = byteArrayOf(26, 29, 0, 1).decodeToString())

        assertNull(stored.readableFwVersion)
        assertEquals("2026.29.0-dev", AdvertisementData.parse(serviceDataWithUuid())?.readableFwVersion)
    }

    @Test
    fun firmwareVersion_ordersByYearWeekAndCount_andADevBuildBeforeItsRelease() {
        val dev = FirmwareVersion(2026, 29, 1, dev = true)
        val release = FirmwareVersion(2026, 29, 1, dev = false)

        assertTrue(dev < release)
        assertTrue(release < FirmwareVersion(2026, 29, 2, dev = true))
        assertTrue(FirmwareVersion(2026, 52, 9, dev = false) < FirmwareVersion(2027, 1, 0, dev = false))
    }

    private companion object {
        const val SERVICE_DATA_16 = 0x16
    }
}
