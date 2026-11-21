package com.snapshotpb.configuration.share

import com.snapshotpb.configuration.common.share.ShareRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Gives the file that the user selected, or null when the user cancels. */
internal fun interface SaveTargetChooser {
    suspend fun choose(title: String, suggestedName: String): File?
}

/** Saves a [ShareRequest.Text] to a file that the user selects in a save dialog. */
internal class DesktopFileSaver(
    private val chooser: SaveTargetChooser,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Returns the written file, or null when the user cancels. A write failure throws. */
    suspend fun save(request: ShareRequest.Text): File? {
        val target = chooser.choose(request.title, request.fileName) ?: return null
        withContext(ioDispatcher) {
            writeUtf8(target, request.text)
        }
        return target
    }
}

/** Gives the selected file of a FileDialog. Null when [file] is null or empty (a cancel). */
internal fun saveTargetOf(directory: String?, file: String?): File? {
    if (file.isNullOrEmpty()) return null
    return if (directory == null) File(file) else File(directory, file)
}

/** Writes [text] to [file] as UTF-8, and replaces the old content. */
internal fun writeUtf8(file: File, text: String) {
    file.writeText(text, Charsets.UTF_8)
}

/**
 * Opens java.awt.FileDialog in SAVE mode on the Swing thread (Dispatchers.Swing from
 * kotlinx-coroutines-swing). [parent] gives the owner window, or null.
 */
internal class AwtSaveTargetChooser(private val parent: () -> Frame?) : SaveTargetChooser {
    override suspend fun choose(title: String, suggestedName: String): File? =
        withContext(Dispatchers.Swing) {
            val dialog = FileDialog(parent(), title, FileDialog.SAVE)
            try {
                dialog.file = suggestedName
                dialog.isVisible = true
                saveTargetOf(dialog.directory, dialog.file)
            } finally {
                dialog.dispose()
            }
        }
}
