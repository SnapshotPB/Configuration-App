package com.snapshotpb.configuration.common.composable

import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the assets for the default device image in `core:resources` against
 * [DEFAULT_DEVICE_IMAGE_BACKGROUND].
 *
 * `art/default-device-image/make_default_device_image.py` writes the palette PNG files. The
 * Kotlin constant must match the edge colour of every file, or a seam shows round the image.
 * The `jvmTest` task declares `composeResources` as an input in `build.gradle.kts`, so a change
 * to an image runs this test again.
 */
class DefaultDeviceImageTest {

    @Test
    fun everyDensityHasTheImage() {
        for ((density, _) in DENSITIES) {
            val file = imageFile(density)
            assertTrue(file.isFile, "No file at ${file.path}")
            assertNotNull(ImageIO.read(file), "ImageIO did not decode ${file.path}")
        }
    }

    @Test
    fun theSizesFollowTheDensity() {
        val reference = readImage("xxxhdpi")
        for ((density, scale) in DENSITIES) {
            val image = readImage(density)
            val expectedWidth = (reference.width * scale / 4.0).roundToInt()
            val expectedHeight = (reference.height * scale / 4.0).roundToInt()
            assertTrue(
                abs(image.width - expectedWidth) <= 1,
                "$density width ${image.width} is not within 1 pixel of $expectedWidth",
            )
            assertTrue(
                abs(image.height - expectedHeight) <= 1,
                "$density height ${image.height} is not within 1 pixel of $expectedHeight",
            )
        }
    }

    @Test
    fun theEdgeIsTheBackgroundColour() {
        val expectedArgb = DEFAULT_DEVICE_IMAGE_BACKGROUND.toArgb()
        for ((density, _) in DENSITIES) {
            val image = readImage(density)
            val lastX = image.width - 1
            val lastY = image.height - 1
            for (x in 0 until image.width) {
                assertEquals(
                    expectedArgb,
                    image.getRGB(x, 0),
                    "$density pixel ($x, 0) does not match DEFAULT_DEVICE_IMAGE_BACKGROUND",
                )
                assertEquals(
                    expectedArgb,
                    image.getRGB(x, lastY),
                    "$density pixel ($x, $lastY) does not match DEFAULT_DEVICE_IMAGE_BACKGROUND",
                )
            }
            for (y in 0 until image.height) {
                assertEquals(
                    expectedArgb,
                    image.getRGB(0, y),
                    "$density pixel (0, $y) does not match DEFAULT_DEVICE_IMAGE_BACKGROUND",
                )
                assertEquals(
                    expectedArgb,
                    image.getRGB(lastX, y),
                    "$density pixel ($lastX, $y) does not match DEFAULT_DEVICE_IMAGE_BACKGROUND",
                )
            }
        }
    }

    @Test
    fun theOldIconIsGone() {
        val drawableDirs = File(COMPOSE_RESOURCES_DIR)
            .listFiles { file -> file.isDirectory && file.name.startsWith("drawable") }
            .orEmpty()
        assertTrue(drawableDirs.isNotEmpty(), "No drawable folder in $COMPOSE_RESOURCES_DIR")
        for (dir in drawableDirs) {
            val oldIcon = File(dir, OLD_ICON_FILE_NAME)
            assertFalse(oldIcon.exists(), "Old icon still exists at ${oldIcon.path}")
        }
    }

    private fun imageFile(density: String): File =
        File(COMPOSE_RESOURCES_DIR, "drawable-$density/$IMAGE_FILE_NAME")

    private fun readImage(density: String): BufferedImage {
        val file = imageFile(density)
        return assertNotNull(ImageIO.read(file), "ImageIO did not decode ${file.path}")
    }

    private companion object {
        /** Relative to the project directory, which is the working directory of `jvmTest`. */
        const val COMPOSE_RESOURCES_DIR = "../resources/src/commonMain/composeResources"
        const val IMAGE_FILE_NAME = "default-device-image.png"
        const val OLD_ICON_FILE_NAME = "shooter-icon.png"
        val DENSITIES = listOf(
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0,
        )
    }
}
