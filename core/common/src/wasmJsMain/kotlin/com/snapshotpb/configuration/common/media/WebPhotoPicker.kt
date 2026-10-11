@file:OptIn(ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.common.media

import com.snapshotpb.configuration.model.Photo
import com.toxicbakery.logging.Arbor
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine

private val log = Arbor.tag("WebPhotoPicker")

/** The longest side of a photo that the web app keeps, in pixels. The badge is not larger. */
internal const val WEB_PHOTO_MAX_SIDE = 1024

/**
 * True when this browser can select a photo and keep it after a reload: it has a document for the
 * file input, `createImageBitmap` to read the image, and IndexedDB for the store.
 */
internal fun webPhotoCaptureSupported(): Boolean = js(
    "(typeof document !== 'undefined' && typeof createImageBitmap === 'function' && typeof indexedDB !== 'undefined' && !!indexedDB)"
)

/**
 * Lets the user select an image through `<input type="file">`, which accepts images only. With
 * [camera], the input has `capture`, so a mobile browser opens the camera. A desktop browser
 * ignores `capture` and shows the file chooser. Gives null when the user closes the chooser with
 * no file.
 *
 * The photo has a `data:` URI. Coil shows it before the save, and the store reads it on the save.
 * [prepareWebPhoto] scales the image down to [WEB_PHOTO_MAX_SIDE] and encodes it again. Thus the
 * EXIF orientation of a camera photo applies, and a large photo does not fill the store.
 *
 * A cancel of the caller closes the input, so a new call can open the chooser again.
 */
internal suspend fun pickWebPhoto(camera: Boolean): Photo? {
    val file = chooseImageFileOrNull(camera) ?: return null
    return try {
        prepareWebPhoto(file, WEB_PHOTO_MAX_SIDE).await<WebPhotoJs>().toPhoto()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        log.w(error) { "The selected file is not an image that this browser can read" }
        null
    }
}

/** A photo that [prepareWebPhoto] made. */
internal external interface WebPhotoJs : JsAny {
    val uri: String
    val width: Int
    val height: Int
    val mimeType: String
    val fileName: String?
}

internal fun WebPhotoJs.toPhoto(): Photo = Photo(
    uri = uri,
    width = width,
    height = height,
    fileName = fileName,
    mimeType = mimeType,
)

/** The selected `File`, or null when the user closes the chooser or the browser does not open it. */
private suspend fun chooseImageFileOrNull(camera: Boolean): JsAny? = suspendCancellableCoroutine { continuation ->
    val close = openImageChooser(camera) { file -> if (continuation.isActive) continuation.resume(file) }
    continuation.invokeOnCancellation { callFunction(close) }
}

/**
 * Opens the file chooser, and calls [onDone] one time with the selected `File`, or with null when
 * the user closes the chooser. Returns a function that removes the input with no call to [onDone].
 *
 * The input is in the document while the chooser is open, because some browsers send no `change`
 * event to an input outside the document. A browser opens the chooser only after a user action.
 * When `navigator.userActivation` shows that there is none, [onDone] gets null immediately, so the
 * selection ends and the next tap can open the chooser.
 */
private fun openImageChooser(camera: Boolean, onDone: (JsAny?) -> Unit): JsAny = js(
    """(() => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        if (camera) input.setAttribute('capture', 'environment');
        input.style.display = 'none';
        let done = false;
        const close = () => {
            if (done) return false;
            done = true;
            input.remove();
            return true;
        };
        const finish = (file) => { if (close()) onDone(file); };
        if (navigator.userActivation && !navigator.userActivation.isActive) {
            finish(null);
            return close;
        }
        input.addEventListener('change', () => finish(input.files && input.files.length > 0 ? input.files[0] : null));
        input.addEventListener('cancel', () => finish(null));
        document.body.appendChild(input);
        input.click();
        return close;
    })()"""
)

private fun callFunction(callable: JsAny): Unit = js("{ callable(); }")

/**
 * Scales [file] (a `Blob` with an image) down to [maxSide] and encodes it as a `data:` URI. A PNG
 * stays a PNG, so its transparent pixels stay transparent. Any other image becomes a JPEG. The
 * Promise rejects when the browser cannot read the image.
 */
internal fun prepareWebPhoto(file: JsAny, maxSide: Int): Promise<WebPhotoJs> = js(
    """(async () => {
        const mimeType = file.type === 'image/png' ? 'image/png' : 'image/jpeg';
        const bitmap = await createImageBitmap(file);
        try {
            const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
            const width = Math.max(1, Math.round(bitmap.width * scale));
            const height = Math.max(1, Math.round(bitmap.height * scale));
            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;
            canvas.getContext('2d').drawImage(bitmap, 0, 0, width, height);
            return {
                uri: canvas.toDataURL(mimeType, 0.9),
                width: width,
                height: height,
                mimeType: mimeType,
                fileName: (typeof file.name === 'string') ? file.name : null,
            };
        } finally {
            bitmap.close();
        }
    })()"""
)
