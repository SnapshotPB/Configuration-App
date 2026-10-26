package com.snapshotpb.configuration.bluetooth.smp

/** The SMP layer codes (`mcumgr_err_t`, `mgmt_defines.h`). They are in the `rc` value. */
enum class McuMgrError(val code: Int) {
    EOK(0),
    EUNKNOWN(1),
    ENOMEM(2),
    EINVAL(3),
    ETIMEOUT(4),
    ENOENT(5),
    EBADSTATE(6),
    EMSGSIZE(7),
    ENOTSUP(8),
    ECORRUPT(9),
    EBUSY(10),
    EACCESSDENIED(11),
    UNSUPPORTED_TOO_OLD(12),
    UNSUPPORTED_TOO_NEW(13);

    companion object {
        fun fromCode(code: Int): McuMgrError? = entries.firstOrNull { it.code == code }

        internal fun describe(code: Int): String = fromCode(code)?.let { "${it.name} ($code)" } ?: "rc $code"
    }
}

/** The image group codes (`img_mgmt_err_code_t`, `img_mgmt.h`). They are in `err.rc`. */
enum class ImageManagementError(val code: Int) {
    OK(0),
    UNKNOWN(1),
    FLASH_CONFIG_QUERY_FAIL(2),
    NO_IMAGE(3),
    NO_TLVS(4),
    INVALID_TLV(5),
    TLV_MULTIPLE_HASHES_FOUND(6),
    TLV_INVALID_SIZE(7),
    HASH_NOT_FOUND(8),
    NO_FREE_SLOT(9),
    FLASH_OPEN_FAILED(10),
    FLASH_READ_FAILED(11),
    FLASH_WRITE_FAILED(12),
    FLASH_ERASE_FAILED(13),
    INVALID_SLOT(14),
    NO_FREE_MEMORY(15),
    FLASH_CONTEXT_ALREADY_SET(16),
    FLASH_CONTEXT_NOT_SET(17),
    FLASH_AREA_DEVICE_NULL(18),
    INVALID_PAGE_OFFSET(19),
    INVALID_OFFSET(20),
    INVALID_LENGTH(21),
    INVALID_IMAGE_HEADER(22),
    INVALID_IMAGE_HEADER_MAGIC(23),
    INVALID_HASH(24),
    INVALID_FLASH_ADDRESS(25),
    VERSION_GET_FAILED(26),
    CURRENT_VERSION_IS_NEWER(27),
    IMAGE_ALREADY_PENDING(28),
    INVALID_IMAGE_VECTOR_TABLE(29),
    INVALID_IMAGE_TOO_LARGE(30),
    INVALID_IMAGE_DATA_OVERRUN(31),
    IMAGE_CONFIRMATION_DENIED(32),
    IMAGE_SETTING_TEST_TO_ACTIVE_DENIED(33),
    ACTIVE_SLOT_NOT_KNOWN(34);

    companion object {
        fun fromCode(code: Int): ImageManagementError? = entries.firstOrNull { it.code == code }
    }
}

/** The OS group codes (`os_mgmt_err_code_t`, `os_mgmt.h`). They are in `err.rc`. */
enum class OsManagementError(val code: Int) {
    OK(0),
    UNKNOWN(1),
    INVALID_FORMAT(2),
    QUERY_YIELDS_NO_ANSWER(3),
    RTC_NOT_SET(4),
    RTC_COMMAND_FAILED(5),
    QUERY_RESPONSE_VALUE_NOT_VALID(6),
    HEAP_STATS_FETCH_FAILED(7);

    companion object {
        fun fromCode(code: Int): OsManagementError? = entries.firstOrNull { it.code == code }
    }
}

/** A text for a v2 group error: the name of the code when this client knows it. */
internal fun describeGroupError(errorGroup: Int, rc: Int): String {
    val name = when (errorGroup) {
        SmpGroup.IMAGE -> ImageManagementError.fromCode(rc)?.name
        SmpGroup.OS -> OsManagementError.fromCode(rc)?.name
        else -> null
    }
    return if (name != null) "$name (group $errorGroup, rc $rc)" else "group $errorGroup rc $rc"
}
