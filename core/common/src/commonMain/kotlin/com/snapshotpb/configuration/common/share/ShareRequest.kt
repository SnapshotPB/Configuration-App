package com.snapshotpb.configuration.common.share

/** A request to give a document to the platform share or save UI. */
sealed interface ShareRequest {
    /** A UTF-8 text file. [title] is the translated title of the share or save UI. */
    data class Text(
        val fileName: String,
        val title: String,
        val text: String,
        val mimeType: String = "text/plain",
    ) : ShareRequest
}
