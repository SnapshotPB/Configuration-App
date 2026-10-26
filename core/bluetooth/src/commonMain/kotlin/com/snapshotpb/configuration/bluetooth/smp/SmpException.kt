package com.snapshotpb.configuration.bluetooth.smp

/** Base class for the failures of [SmpClient], [ImageManagement] and [OsManagement]. */
sealed class SmpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The SMP service is on the peripheral, but its characteristic is not. */
class SmpCharacteristicMissing(uuid: String) :
    SmpException("SMP characteristic $uuid not found")

/** A write to the SMP characteristic failed, or the link closed. */
class SmpTransportFailure(message: String, cause: Throwable? = null) :
    SmpException(message, cause)

/**
 * The Bluetooth engine has no write without response that reports its result. In BlueFalcon 3.7.14
 * this is `JsEngine` (web) and `MacosJvmEngine` (desktop on macOS). The update is not available on
 * such a platform until its engine gets the typed write.
 */
class SmpWriteUnsupported :
    SmpException("The Bluetooth engine of this platform cannot write without response with a result")

/** No response to the request with [sequence] came in the time limit. */
class SmpTimeout(val group: Int, val command: Int, val sequence: Int) :
    SmpException("No SMP response to group $group command $command (sequence $sequence)")

/** A frame or a CBOR body does not agree with the SMP specification. */
class SmpMalformedFrame(message: String, cause: Throwable? = null) :
    SmpException(message, cause)

/**
 * The board answered with an `rc` value that is not zero. This is the SMP v1 form of each error,
 * and the v2 form of an error that comes from the SMP layer, not from a group. A refusal from an
 * application hook can also use this form in v2. [rc] is an `mcumgr_err_t` code.
 */
class SmpReturnCodeError(val group: Int, val command: Int, val rc: Int) :
    SmpException("SMP group $group command $command failed: ${McuMgrError.describe(rc)}") {
    /** The known name of [rc], or null for a code from a later Zephyr or from the application. */
    val error: McuMgrError? get() = McuMgrError.fromCode(rc)
}

/**
 * The board answered with an `err` map (SMP v2). [errorGroup] is the group that defines [rc]. It
 * is usually the group of the request, but an application hook can report a code of its own group.
 */
class SmpGroupError(val group: Int, val command: Int, val errorGroup: Int, val rc: Int) :
    SmpException("SMP group $group command $command failed: ${describeGroupError(errorGroup, rc)}") {
    /** The image group code, or null when [errorGroup] is not the image group or the code is unknown. */
    val imageError: ImageManagementError?
        get() = if (errorGroup == SmpGroup.IMAGE) ImageManagementError.fromCode(rc) else null

    /** The OS group code, or null when [errorGroup] is not the OS group or the code is unknown. */
    val osError: OsManagementError?
        get() = if (errorGroup == SmpGroup.OS) OsManagementError.fromCode(rc) else null
}

/**
 * The board did not move its upload offset for several requests in sequence. [offset] is the
 * offset of the last request, and [boardOffset] is the offset of the last answer.
 */
class SmpUploadStalled(val offset: Long, val boardOffset: Long) :
    SmpException("The board did not accept the upload at offset $offset (it asks for $boardOffset)")

/**
 * The board has the full image, but the SHA-256 of the data in the slot is not the `sha` of the
 * first chunk. The board answers `match: false` only when `CONFIG_IMG_ENABLE_IMAGE_CHECK` is on.
 */
class SmpImageMismatch :
    SmpException("The board reports that the uploaded data does not have the SHA-256 of the image")
