package com.snapshotpb.configuration.bluetooth.log

internal object BluetoothLogRedactor {
    private val UUID_PATTERN: Regex =
        Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

    private val ADDRESS_PATTERN: Regex =
        Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

    private val C0_EXCEPT_TAB_PATTERN: Regex =
        Regex("[\u0000-\u0008\u000A-\u001F]")

    private const val UUID_MASK: String = "<uuid>"
    private const val ADDRESS_MASK: String = "XX:XX:XX:XX:XX:XX"

    /** Masks each Bluetooth address and each UUID, and removes the C0 control characters except the tab. */
    fun clean(text: String): String {
        val withoutUuids = text.replace(UUID_PATTERN, UUID_MASK)
        val withoutAddresses = withoutUuids.replace(ADDRESS_PATTERN, ADDRESS_MASK)
        return withoutAddresses.replace(C0_EXCEPT_TAB_PATTERN, "")
    }
}
