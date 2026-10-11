package com.snapshotpb.configuration.bluetooth.ots

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid

/**
 * UUIDs defined by the Bluetooth SIG Object Transfer Service v1.0 specification.
 *
 * Short-form 16-bit UUIDs are expanded to the full Bluetooth Base UUID via
 * [toUuid] so they compare equal to whatever the platform engine surfaces.
 */
object OtsUuids {
    /** Object Transfer Service. */
    val Service: Uuid = "1825".toUuid()

    /** OTS Feature characteristic; 8 bytes (OACP features LE + OLCP features LE). */
    val Feature: Uuid = "2ABD".toUuid()

    /** Object Name; UTF-8, up to 120 bytes. */
    val ObjectName: Uuid = "2ABE".toUuid()

    /** Object Type; 16-bit or 128-bit UUID payload. */
    val ObjectType: Uuid = "2ABF".toUuid()

    /** Object Size; 8 bytes (current size LE u32 + allocated size LE u32). */
    val ObjectSize: Uuid = "2AC0".toUuid()

    /** Object First-Created timestamp; 7 bytes. */
    val ObjectFirstCreated: Uuid = "2AC1".toUuid()

    /** Object Last-Modified timestamp; 7 bytes. */
    val ObjectLastModified: Uuid = "2AC2".toUuid()

    /** Object ID; 6 bytes, little-endian ULEB48. */
    val ObjectId: Uuid = "2AC3".toUuid()

    /** Object Properties; 4-byte LE bitmask. */
    val ObjectProperties: Uuid = "2AC4".toUuid()

    /** Object Action Control Point; write + indicate. */
    val Oacp: Uuid = "2AC5".toUuid()

    /** Object List Control Point; write + indicate. */
    val Olcp: Uuid = "2AC6".toUuid()

    /** Object List Filter; read/write. */
    val ListFilter: Uuid = "2AC7".toUuid()

    /** Object Changed; indicate. */
    val ObjectChanged: Uuid = "2AC8".toUuid()

    // --- Vendor extensions (NOT part of the OTS specification) ------------------
    //
    // The OTS spec defines no GATT characteristic for object content and no
    // standard Object Type for a "Device" object. The two UUIDs below are a
    // private firmware contract: a vendor Object Type that identifies the
    // Device configuration object, and a vendor notify characteristic the
    // firmware pushes content chunks on in response to OACP READ (standard OTS
    // would stream content over an L2CAP CoC, unavailable on Windows desktop).
    //
    // These are the vendor UUIDs the deployed firmware actually uses; the read/write flow is
    // verified end-to-end against a real board with these values. They are a private firmware
    // contract, not placeholders — changing either breaks every client at once.

    /**
     * Object Type UUID identifying the vendor "Device" configuration object.
     *
     * Vendor-assigned; not in the OTS spec.
     */
    val DeviceObjectType: Uuid = "00000001-5350-4221-0000-5350425f4f54".toUuid()

    /**
     * Vendor "Object Data" characteristic. After the OACP READ handshake the
     * firmware notifies object-content chunks on this characteristic in lieu of
     * the spec-mandated L2CAP connection-oriented channel.
     *
     * Vendor-assigned; not in the OTS spec.
     */
    val ObjectData: Uuid = "00000002-5350-4221-0000-5350425f4f54".toUuid()
}