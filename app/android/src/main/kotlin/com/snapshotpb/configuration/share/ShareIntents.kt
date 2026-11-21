package com.snapshotpb.configuration.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.snapshotpb.configuration.common.share.ShareRequest

/** Builds a system share chooser intent for a text file at [uri]. */
internal fun shareChooserIntent(uri: Uri, request: ShareRequest.Text): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = request.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, request.title)
        clipData = ClipData.newRawUri(request.title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, request.title)
}
