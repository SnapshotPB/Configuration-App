package com.snapshotpb.configuration.common.composable

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sizes are the constraints that the screens measured in the web app, in dp. A web page
 * gives one dp to each CSS pixel at each device pixel ratio.
 */
class TwoPaneLayoutTest {

    @Test
    fun wideDesktopWindow_showsTwoPanes() {
        // The device list in a 1400 x 900 window. Its title takes 84 dp of the height.
        assertTrue(fitsTwoPanes(maxWidth = 1400.dp, maxHeight = 816.dp))
    }

    @Test
    fun phoneOnItsSide_showsTwoPanes() {
        assertTrue(fitsTwoPanes(maxWidth = 844.dp, maxHeight = 390.dp))
    }

    @Test
    fun portraitPhone_showsOneColumn() {
        assertFalse(fitsTwoPanes(maxWidth = 390.dp, maxHeight = 760.dp))
    }

    @Test
    fun shortFrameOnAPhone_showsOneColumn() {
        // The device list in a 390 x 398 dp frame, which is the size of the app frame of the
        // board viewer on a phone. The title of the list takes 84 dp. The container is wider
        // than it is tall, but 390 dp is too narrow for two panes.
        assertFalse(fitsTwoPanes(maxWidth = 390.dp, maxHeight = 314.dp))
    }

    @Test
    fun narrowColumnOnItsSide_showsOneColumn() {
        // The 420 dp side column of the board viewer on a phone on its side.
        assertFalse(fitsTwoPanes(maxWidth = 420.dp, maxHeight = 390.dp))
    }

    @Test
    fun portraitTablet_showsOneColumn() {
        assertFalse(fitsTwoPanes(maxWidth = 800.dp, maxHeight = 1280.dp))
    }

    @Test
    fun minimumWidth_showsTwoPanes() {
        assertTrue(fitsTwoPanes(maxWidth = TWO_PANE_MIN_WIDTH, maxHeight = 400.dp))
        assertFalse(fitsTwoPanes(maxWidth = TWO_PANE_MIN_WIDTH - 1.dp, maxHeight = 400.dp))
    }

    @Test
    fun squareScreen_showsOneColumn() {
        assertFalse(fitsTwoPanes(maxWidth = 900.dp, maxHeight = 900.dp))
    }
}
