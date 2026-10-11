@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.common.media

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toUri
import com.snapshotpb.configuration.store.WebPhotoStore
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

/**
 * Tests of the web photo path in a real browser: Coil loads a stored photo through
 * [WebPhotoFetcher], and [prepareWebPhoto] scales a selected image down.
 */
class WebPhotoTest {

    private val context = PlatformContext.INSTANCE
    private val imageLoader = ImageLoader.Builder(context)
        .components { add(WebPhotoFetcher.Factory()) }
        .build()

    @Test
    fun coil_showsAStoredPhotoFromItsPhotoPath() = runTest {
        val name = "device_photo_test_${Random.nextLong()}_1"
        WebPhotoStore.shared.save(name, Base64.decode(PNG_2X1_BASE64))

        // The photo path that the store gives: `file://` and `StoreIOProvider.pathFor`.
        val result = imageLoader.execute(ImageRequest.Builder(context).data("file://snapshotpb/$name").build())

        val image = assertIs<SuccessResult>(result, "result: $result").image
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        WebPhotoStore.shared.delete(name)
    }

    @Test
    fun coil_givesAnErrorForAPhotoThatIsNotStored() = runTest {
        val result = imageLoader.execute(
            ImageRequest.Builder(context).data("file://snapshotpb/device_photo_missing_1").build()
        )

        assertIs<ErrorResult>(result)
    }

    @Test
    fun coil_showsASelectedPhotoFromItsDataUri() = runTest {
        val result = imageLoader.execute(
            ImageRequest.Builder(context).data("data:image/png;base64,$PNG_2X1_BASE64").build()
        )

        assertIs<SuccessResult>(result, "result: $result")
    }

    @Test
    fun factory_takesOnlyTheFileUriOfAPhoto() {
        val factory = WebPhotoFetcher.Factory()
        val options = coil3.request.Options(context)

        assertNotNull(factory.create("file://snapshotpb/device_photo_x_1".toUri(), options, imageLoader))
        assertNull(factory.create("data:image/png;base64,$PNG_2X1_BASE64".toUri(), options, imageLoader))
        assertNull(factory.create("https://example.com/photo.png".toUri(), options, imageLoader))
        assertNull(factory.create("file://snapshotpb/devices.json".toUri(), options, imageLoader))
    }

    @Test
    fun prepareWebPhoto_scalesALargeImageDown() = runTest {
        val file = canvasImage(width = 2000, height = 1000, mimeType = "image/png").await<JsAny>()

        val photo = prepareWebPhoto(file, WEB_PHOTO_MAX_SIDE).await<WebPhotoJs>().toPhoto()

        assertEquals(WEB_PHOTO_MAX_SIDE, photo.width)
        assertEquals(WEB_PHOTO_MAX_SIDE / 2, photo.height)
        assertEquals("image/png", photo.mimeType)
        assertTrue(photo.uri.startsWith("data:image/png;base64,"), photo.uri.take(40))
    }

    @Test
    fun prepareWebPhoto_encodesAnImageThatIsNotAPngAsAJpeg() = runTest {
        val file = canvasImage(width = 40, height = 30, mimeType = "image/webp").await<JsAny>()

        val photo = prepareWebPhoto(file, WEB_PHOTO_MAX_SIDE).await<WebPhotoJs>().toPhoto()

        assertEquals(40, photo.width, "a small image keeps its size")
        assertEquals(30, photo.height)
        assertEquals("image/jpeg", photo.mimeType)
        assertTrue(photo.uri.startsWith("data:image/jpeg;base64,"))
    }

    private companion object {
        /** A PNG of 2 x 1 pixels. */
        const val PNG_2X1_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8AAQv8BD/kD/YURmXYAAAAASUVORK5CYII="
    }
}

/** A `Blob` with an image of [width] x [height] pixels, of the type [mimeType]. */
private fun canvasImage(width: Int, height: Int, mimeType: String): Promise<JsAny> = js(
    """new Promise((resolve, reject) => {
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext('2d');
        context.fillStyle = '#c04020';
        context.fillRect(0, 0, width, height);
        canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('no blob')), mimeType);
    })"""
)
