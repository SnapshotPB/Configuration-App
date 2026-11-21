package com.snapshotpb.configuration.bluetooth.log

import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothLogRedactorTest {

    @Test
    fun clean_addressWithColons_masksAddress() {
        val cleaned = BluetoothLogRedactor.clean("peer AA:BB:CC:DD:EE:FF connected")

        assertEquals("peer XX:XX:XX:XX:XX:XX connected", cleaned)
    }

    @Test
    fun clean_addressWithHyphens_masksAddress() {
        val cleaned = BluetoothLogRedactor.clean("peer aa-bb-cc-dd-ee-ff connected")

        assertEquals("peer XX:XX:XX:XX:XX:XX connected", cleaned)
    }

    @Test
    fun clean_lowerAndUpperCaseUuids_changesBothToUuidMask() {
        val input = "svc 6e400001-b5a3-f393-e0a9-e50e24dcca9e tx 6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

        val cleaned = BluetoothLogRedactor.clean(input)

        assertEquals("svc <uuid> tx <uuid>", cleaned)
    }

    @Test
    fun clean_c0ControlCharacters_removesThem() {
        val input = "a\u0000b\u0007c\u001Bd"

        val cleaned = BluetoothLogRedactor.clean(input)

        assertEquals("abcd", cleaned)
    }

    @Test
    fun clean_tabCharacter_keepsTab() {
        val cleaned = BluetoothLogRedactor.clean("col1\tcol2")

        assertEquals("col1\tcol2", cleaned)
    }

    @Test
    fun clean_textWithNoMatch_keepsTextSame() {
        val input = "[00:00:01.000,000] app: normal log line 123"

        val cleaned = BluetoothLogRedactor.clean(input)

        assertEquals(input, cleaned)
    }

    @Test
    fun clean_lineWithAddressAndUuid_masksBoth() {
        val input = "peer AC:EB:E6:81:A8:5C uses 6E400001-B5A3-F393-E0A9-E50E24DCCA9E"

        val cleaned = BluetoothLogRedactor.clean(input)

        assertEquals("peer XX:XX:XX:XX:XX:XX uses <uuid>", cleaned)
    }
}
