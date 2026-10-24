package com.snapshotpb.configuration.bluetooth.smp

/*
 * SMP frames for the tests of the smp package.
 *
 * EACH FRAME IN THIS FILE IS DERIVED FROM SOURCE. No frame was recorded from a board yet. The bytes
 * were written by hand from smp_protocol.rst, smp_group_0.rst and smp_group_1.rst of Zephyr 4.4 and
 * from the encoder and decoder calls of its server code (smp/src/smp.c, img_mgmt.c,
 * img_mgmt_state.c, zephyr_img_mgmt.c, os_mgmt.c). Work item 2 of docs/firmware-update-plan.md
 * records frames with the mcumgr CLI on the bench. Replace each frame with the recorded one, keep
 * its name, and remove this paragraph when no derived frame is left.
 *
 * The responses follow the Kconfig of the bench firmware (Firmware build b2-v2-ota, app/ota.conf):
 * CONFIG_ZCBOR_CANONICAL off, CONFIG_MCUMGR_GRP_IMG_FRUGAL_LIST off, CONFIG_IMG_ENABLE_IMAGE_CHECK
 * off (no `match`), CONFIG_MCUMGR_SMP_LEGACY_RC_BEHAVIOUR off (no `rc: 0`),
 * CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE 2475, CONFIG_MCUMGR_TRANSPORT_NETBUF_COUNT 4 and
 * CONFIG_BOARD_TARGET "snapshot_v2/esp32c3". Without CONFIG_ZCBOR_CANONICAL, zcbor writes each map
 * and list with an indefinite length (0xBF ... 0xFF, 0x9F ... 0xFF), so the responses have that
 * form. The frames named CANONICAL have the definite-length form of a firmware with
 * CONFIG_ZCBOR_CANONICAL. The requests have definite lengths, because the client writes them so.
 *
 * Header byte 0: bits 0-2 operation (0 read, 1 read response, 2 write, 3 write response), bits 3-4
 * version (0 v1, 1 v2). So 0x08 is a v2 read, 0x09 a v2 read response, 0x0A a v2 write, 0x0B a v2
 * write response, and 0x03 a v1 write response.
 */

/** Parses hexadecimal text. Spaces and line ends between the bytes are ignored. */
internal fun hex(text: String): ByteArray {
    val digits = text.filterNot { it.isWhitespace() }
    require(digits.length % 2 == 0) { "Odd number of hexadecimal digits" }
    return ByteArray(digits.length / 2) { index -> digits.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

/** The 32 bytes 0x00 to 0x1F, as the SHA-256 of an image file. */
internal const val FILE_SHA_HEX =
    "00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F"

/** The 32 bytes 0xA0 to 0xBF, as the image hash of slot 0. */
internal const val SLOT0_HASH_HEX =
    "A0 A1 A2 A3 A4 A5 A6 A7 A8 A9 AA AB AC AD AE AF B0 B1 B2 B3 B4 B5 B6 B7 B8 B9 BA BB BC BD BE BF"

/** The 32 bytes 0xC0 to 0xDF, as the image hash of slot 1. */
internal const val SLOT1_HASH_HEX =
    "C0 C1 C2 C3 C4 C5 C6 C7 C8 C9 CA CB CC CD CE CF D0 D1 D2 D3 D4 D5 D6 D7 D8 D9 DA DB DC DD DE DF"

internal object SmpFixtures {

    // --- Image group (1) ---------------------------------------------------------------------

    /** `image list`: v2 read, group 1, command 0, sequence 0, body {} (1 byte). */
    val IMAGE_LIST_REQUEST = hex("08 00 0001 0001 00 00" + "A0")

    /**
     * The answer to [IMAGE_LIST_REQUEST] from a board that runs 1.2.3 in slot 0 and holds 1.3.0 in
     * slot 1 for a test boot. img_mgmt_state_read() writes "images" (a list of one map per slot)
     * and "splitStatus". Each slot map has slot, version, hash and the five flags. Body: 244 bytes.
     */
    val IMAGE_LIST_RESPONSE = hex(
        "09 00 00F4 0001 00 00" +
            "BF" +
            "66 696D61676573" + // "images"
            "9F" +
            "BF" +
            "64 736C6F74 00" + // "slot": 0
            "67 76657273696F6E 65 312E322E33" + // "version": "1.2.3"
            "64 68617368 5820 $SLOT0_HASH_HEX" + // "hash": h'A0..BF'
            "68 626F6F7461626C65 F5" + // "bootable": true
            "67 70656E64696E67 F4" + // "pending": false
            "69 636F6E6669726D6564 F5" + // "confirmed": true
            "66 616374697665 F5" + // "active": true
            "69 7065726D616E656E74 F4" + // "permanent": false
            "FF" +
            "BF" +
            "64 736C6F74 01" + // "slot": 1
            "67 76657273696F6E 65 312E332E30" + // "version": "1.3.0"
            "64 68617368 5820 $SLOT1_HASH_HEX" + // "hash": h'C0..DF'
            "68 626F6F7461626C65 F5" + // "bootable": true
            "67 70656E64696E67 F5" + // "pending": true
            "69 636F6E6669726D6564 F4" + // "confirmed": false
            "66 616374697665 F4" + // "active": false
            "69 7065726D616E656E74 F4" + // "permanent": false
            "FF" +
            "FF" +
            "6B 73706C6974537461747573 00" + // "splitStatus": 0
            "FF",
    )

    /**
     * The answer to [IMAGE_LIST_REQUEST] from a firmware with CONFIG_ZCBOR_CANONICAL and
     * CONFIG_MCUMGR_GRP_IMG_FRUGAL_LIST: definite lengths, and a flag only when it is true. Only
     * slot 0 has an image. Body: 98 bytes.
     */
    val IMAGE_LIST_RESPONSE_CANONICAL = hex(
        "09 00 0062 0001 00 00" +
            "A1" +
            "66 696D61676573" + // "images"
            "81" +
            "A6" +
            "64 736C6F74 00" + // "slot": 0
            "67 76657273696F6E 65 312E322E33" + // "version": "1.2.3"
            "64 68617368 5820 $SLOT0_HASH_HEX" + // "hash"
            "68 626F6F7461626C65 F5" + // "bootable": true
            "69 636F6E6669726D6564 F5" + // "confirmed": true
            "66 616374697665 F5", // "active": true
    )

    /** `image test`: v2 write, group 1, command 0, sequence 0, {"hash": slot 1, "confirm": false}. */
    val IMAGE_TEST_REQUEST = hex(
        "0A 00 0031 0001 00 00" +
            "A2" +
            "64 68617368 5820 $SLOT1_HASH_HEX" + // "hash"
            "67 636F6E6669726D F4", // "confirm": false
    )

    /** `image confirm` with no hash: v2 write, group 1, command 0, sequence 0, {"confirm": true}. */
    val IMAGE_CONFIRM_REQUEST = hex("0A 00 000A 0001 00 00" + "A1 67 636F6E6669726D F5")

    /** `image erase` with no slot: v2 write, group 1, command 5, sequence 0, body {}. */
    val IMAGE_ERASE_REQUEST = hex("0A 00 0001 0001 00 05" + "A0")

    /** `image erase` with slot 1. */
    val IMAGE_ERASE_SLOT1_REQUEST = hex("0A 00 0007 0001 00 05" + "A1 64 736C6F74 01")

    /**
     * The first upload request of an image of 4096 bytes, with its first 4 bytes (the MCUboot
     * magic 0x96F3B83D, little-endian). v2 write, group 1, command 1, sequence 1. The map has
     * image, len, off, sha and data, with definite lengths. Body: 68 bytes.
     */
    val UPLOAD_FIRST_REQUEST = hex(
        "0A 00 0044 0001 01 01" +
            "A5" +
            "65 696D616765 00" + // "image": 0
            "63 6C656E 19 1000" + // "len": 4096
            "63 6F6666 00" + // "off": 0
            "63 736861 5820 $FILE_SHA_HEX" + // "sha": h'00..1F'
            "64 64617461 44 3DB8F396", // "data": h'3DB8F396'
    )

    /** A later upload request at offset 65536 with 2 bytes. Sequence 2. Body: 18 bytes. */
    val UPLOAD_NEXT_REQUEST = hex(
        "0A 00 0012 0001 02 01" +
            "A2" +
            "63 6F6666 1A 00010000" + // "off": 65536
            "64 64617461 42 AABB", // "data": h'AABB'
    )

    /** An upload answer: {"off": 256}. Sequence 1. */
    val UPLOAD_RESPONSE_OFF_256 = hex("0B 00 0009 0001 01 01" + "BF 63 6F6666 19 0100 FF")

    /** The answer to the last chunk with the image check: {"off": 4096, "match": true}. Sequence 1. */
    val UPLOAD_RESPONSE_DONE_MATCH = hex(
        "0B 00 0010 0001 01 01" + "BF 63 6F6666 19 1000 65 6D61746368 F5 FF",
    )

    /** A v2 group error: {"err": {"group": 1, "rc": 9}}, NO_FREE_SLOT. Sequence 1. */
    val UPLOAD_ERROR_NO_FREE_SLOT = hex(
        "0B 00 0013 0001 01 01" +
            "BF 63 657272 BF 65 67726F7570 01 62 7263 09 FF FF",
    )

    /** A v1 answer with an error: {"rc": 3}, EINVAL. v1 write response, sequence 1. */
    val UPLOAD_ERROR_V1_EINVAL = hex("03 00 0006 0001 01 01" + "BF 62 7263 03 FF")

    // --- OS group (0) ------------------------------------------------------------------------

    /** `os info -f i`: v2 read, group 0, command 7, sequence 0, {"format": "i"}. */
    val OS_INFO_REQUEST = hex("08 00 000A 0000 00 07" + "A1 66 666F726D6174 61 69")

    /**
     * The answer to [OS_INFO_REQUEST] from the bench firmware: {"output": "snapshot_v2/esp32c3"},
     * CONFIG_BOARD_TARGET. Body: 29 bytes.
     */
    val OS_INFO_RESPONSE = hex(
        "09 00 001D 0000 00 07" +
            "BF 66 6F7574707574 73 736E617073686F745F76322F65737033326333 FF",
    )

    /**
     * The answer to [OS_INFO_REQUEST] from a firmware with the deprecated
     * CONFIG_MCUMGR_GRP_OS_INFO_HARDWARE_INFO_SHORT_HARDWARE_PLATFORM: {"output": "snapshot_v2"}.
     */
    val OS_INFO_RESPONSE_SHORT = hex(
        "09 00 0015 0000 00 07" +
            "BF 66 6F7574707574 6B 736E617073686F745F7632 FF",
    )

    /** `os reset`: v2 write, group 0, command 5, sequence 0, body {}. */
    val OS_RESET_REQUEST = hex("0A 00 0001 0000 00 05" + "A0")

    /** `os reset` with force: {"force": true}. */
    val OS_RESET_FORCE_REQUEST = hex("0A 00 0008 0000 00 05" + "A1 65 666F726365 F5")

    /** The answer to [OS_RESET_REQUEST]: an empty map. */
    val OS_RESET_RESPONSE = hex("0B 00 0002 0000 00 05" + "BF FF")

    /** `os mcumgr_params`: v2 read, group 0, command 6, sequence 0, body {}. */
    val OS_PARAMS_REQUEST = hex("08 00 0001 0000 00 06" + "A0")

    /** The answer to [OS_PARAMS_REQUEST] from the bench firmware: {"buf_size": 2475, "buf_count": 4}. */
    val OS_PARAMS_RESPONSE = hex(
        "09 00 0019 0000 00 06" +
            "BF 68 6275665F73697A65 19 09AB 69 6275665F636F756E74 04 FF",
    )

    /**
     * The answer of a v2 server to a command that it does not have: smp_build_err_rsp() writes
     * {"rc": 8}, ENOTSUP, in the v2 header. Group 0, command 6, sequence 0.
     */
    val OS_PARAMS_NOT_SUPPORTED = hex("09 00 0006 0000 00 06" + "BF 62 7263 08 FF")
}
