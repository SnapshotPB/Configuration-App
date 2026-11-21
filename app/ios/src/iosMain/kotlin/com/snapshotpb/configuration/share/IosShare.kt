@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.snapshotpb.configuration.share

import com.snapshotpb.configuration.common.share.ShareRequest
import com.toxicbakery.logging.Arbor
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

private val log = Arbor.tag("IosShare")

/** The folder in NSTemporaryDirectory() for the shared files. */
internal const val SHARED_FILES_DIRECTORY = "shared-logs"

/**
 * Deletes the old files in `<tmp>/shared-logs`, then writes [text] as UTF-8 to a new file.
 * The file name is the last path component of [fileName]. Gives the file URL, or null when the
 * name is blank or the write fails.
 */
internal fun writeSharedFile(fileName: String, text: String): NSURL? {
    val safeName = fileName.trimEnd('/').substringAfterLast('/')
    if (safeName.isBlank() || safeName == "." || safeName == "..") {
        log.e { "Shared file name is blank" }
        return null
    }

    val fileManager = NSFileManager.defaultManager
    val directoryUrl = NSURL.fileURLWithPath(NSTemporaryDirectory(), isDirectory = true)
        .URLByAppendingPathComponent(SHARED_FILES_DIRECTORY, isDirectory = true)
    val directoryPath = directoryUrl?.path
    if (directoryUrl == null || directoryPath == null) {
        log.e { "Unable to build the shared folder path for $safeName" }
        return null
    }

    val created = fileManager.createDirectoryAtPath(
        path = directoryPath,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    if (!created) {
        log.e { "Unable to create the shared folder for $safeName" }
        return null
    }

    val existingEntries = fileManager.contentsOfDirectoryAtPath(directoryPath, error = null)
    existingEntries?.forEach { entry ->
        val entryName = entry as? String ?: return@forEach
        val entryPath = directoryUrl.URLByAppendingPathComponent(entryName)?.path ?: return@forEach
        fileManager.removeItemAtPath(entryPath, error = null)
    }

    val fileUrl = directoryUrl.URLByAppendingPathComponent(safeName, isDirectory = false)
    val filePath = fileUrl?.path
    if (fileUrl == null || filePath == null) {
        log.e { "Unable to build the shared file path for $safeName" }
        return null
    }

    val bytes = text.encodeToByteArray()
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
    }
    if (!data.writeToFile(filePath, atomically = true)) {
        log.e { "Unable to write shared file $safeName (${text.length} characters)" }
        return null
    }
    return fileUrl
}

/** Presents the share sheet for [fileUrl] from the top view controller. Call it on the main thread. */
internal fun presentShareSheet(fileUrl: NSURL) {
    val topController = findTopViewController()
    if (topController == null) {
        log.e { "No view controller found to present the share sheet" }
        return
    }

    val controller = UIActivityViewController(
        activityItems = listOf(fileUrl),
        applicationActivities = null,
    )
    controller.popoverPresentationController?.let { popover ->
        val anchorView = topController.view
        popover.sourceView = anchorView
        popover.sourceRect = anchorView.bounds.useContents {
            CGRectMake(
                origin.x + size.width / 2.0,
                origin.y + size.height / 2.0,
                0.0,
                0.0,
            )
        }
        popover.permittedArrowDirections = 0uL
    }
    topController.presentViewController(
        viewControllerToPresent = controller,
        animated = true,
        completion = null,
    )
}

/** Writes the file on Dispatchers.Default, then presents the share sheet on Dispatchers.Main. */
internal suspend fun shareOnIos(request: ShareRequest) {
    when (request) {
        is ShareRequest.Text -> {
            // UIActivityViewController has no title, so the app does not use request.title.
            val url = withContext(Dispatchers.Default) {
                writeSharedFile(request.fileName, request.text)
            } ?: return
            withContext(Dispatchers.Main) {
                presentShareSheet(url)
            }
        }
    }
}

private fun findTopViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .mapNotNull { it as? UIWindowScene }
        .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
    val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
    var current = window?.rootViewController ?: return null
    while (true) {
        current = current.presentedViewController ?: return current
    }
}
