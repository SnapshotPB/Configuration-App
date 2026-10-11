package com.snapshotpb.configuration.model

data class SystemConfig(
    /**
     * Indicates the system can link to the Bluetooth menu to enable it.
     */
    val supportsBluetoothEnableLinking: Boolean = false
)
