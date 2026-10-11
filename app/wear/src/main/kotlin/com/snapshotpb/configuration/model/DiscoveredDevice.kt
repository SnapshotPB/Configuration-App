package com.snapshotpb.configuration.model

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val boardType: String?,
    val fwVersion: String?,
)
