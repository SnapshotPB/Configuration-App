package com.snapshotpb.configuration.common.media

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.snapshotpb.configuration.store.WebPhotoStore
import com.snapshotpb.configuration.store.devicePhotoStoreName
import okio.Buffer

/**
 * Loads a stored device photo on the web for Coil. The web has no file system, so a `file:` URI
 * names a photo in [WebPhotoStore], and the last segment of its path is the store name. The UI
 * gives Coil `file://` and the `StoreIOProvider.pathFor` of the store name.
 *
 * Coil reads the photo from IndexedDB, which is asynchronous. The `data:` URI of a photo that the
 * user selected and did not save yet goes to the `DataUriFetcher` of Coil.
 */
internal class WebPhotoFetcher(
    private val name: String,
    private val options: Options,
    private val photos: WebPhotoStore,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = photos.read(name) ?: throw NoSuchElementException("No stored photo $name")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val photos: WebPhotoStore = WebPhotoStore.shared) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != FILE_SCHEME) return null
            val name = data.path?.substringAfterLast('/').orEmpty()
            if (!name.startsWith(devicePhotoStoreName(""))) return null
            return WebPhotoFetcher(name, options, photos)
        }
    }

    private companion object {
        const val FILE_SCHEME = "file"
    }
}

/**
 * Makes the image loader of the page load the stored device photos (see [WebPhotoFetcher]). Call
 * it one time, before the first image loads.
 */
fun installWebPhotoLoader() {
    SingletonImageLoader.setSafe { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components { add(WebPhotoFetcher.Factory()) }
            .build()
    }
}
