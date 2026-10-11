package com.snapshotpb.configuration.bluetooth.ots

/** Base class for failures surfaced by [OtsClient]. */
sealed class OtsException(message: String) : RuntimeException(message)

/** A mandatory characteristic was not present on the discovered OTS service. */
class OtsCharacteristicMissing(uuid: String) :
    OtsException("OTS characteristic $uuid not found")

/** OACP returned an error response. */
class OacpFailure(val opcode: Byte, val result: OacpResult?, raw: Byte) :
    OtsException("OACP opcode 0x${opcode.toUByte().toString(16)} failed: ${result?.name ?: "0x${raw.toUByte().toString(16)}"}")

/** Waited for an indication response longer than the configured timeout. */
class OtsTimeout(message: String) : OtsException(message)

/** No object on the peripheral matched the requested Object Type. */
class OtsObjectNotFound(typeUuid: String) :
    OtsException("No OTS object found with Object Type $typeUuid")

/**
 * The object's content notifications stopped before the full [expected] byte
 * count arrived — either the firmware sent too few bytes or the transfer timed
 * out mid-stream.
 */
class OtsTransferIncomplete(val expected: Long, val received: Int) :
    OtsException("OTS content transfer incomplete: received $received of $expected bytes")
