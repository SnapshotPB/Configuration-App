package com.snapshotpb.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cover for the display-window bounds and for the divergence rule that decides whether the user can
 * push a window to the board.
 */
class DisplayWindowTest {

    @Test
    fun clampedToPanel_boundsTheExtentsFirstAndThenTheCorner() {
        // The board rejects a whole message that carries an out-of-bounds rectangle, so the clamp
        // must produce a legal rectangle for every input, not merely a nearer one.
        assertEquals(
            DisplayWindow(x = 0, y = 6, w = 128, h = 26),
            DisplayWindow(x = 120, y = 30, w = 200, h = 2).clampedToPanel(),
        )
        assertEquals(
            DisplayWindow(x = 0, y = 0, w = 70, h = 26),
            DisplayWindow(x = -40, y = -5, w = 1, h = 1).clampedToPanel(),
        )
        assertEquals(DisplayWindow.FULL_PANEL, DisplayWindow.FULL_PANEL.clampedToPanel())
    }

    @Test
    fun clampedToPanel_isIdempotent() {
        // Three places clamp on the way to the board, so a second clamp must change nothing.
        val once = DisplayWindow(x = 99, y = 99, w = 99, h = 99).clampedToPanel()
        assertEquals(once, once.clampedToPanel())
        assertTrue(once.isWithinPanel())
    }

    @Test
    fun isWithinPanel_rejectsEveryBoundTheBoardEnforces() {
        assertFalse(DisplayWindow(x = 0, y = 0, w = 129, h = 26).isWithinPanel())
        assertFalse(DisplayWindow(x = 0, y = 0, w = 69, h = 26).isWithinPanel())
        assertFalse(DisplayWindow(x = 0, y = 0, w = 70, h = 25).isWithinPanel())
        assertFalse(DisplayWindow(x = 100, y = 0, w = 70, h = 26).isWithinPanel())
        assertFalse(DisplayWindow(x = 0, y = 10, w = 70, h = 26).isWithinPanel())
        assertTrue(DisplayWindow(x = 58, y = 6, w = 70, h = 26).isWithinPanel())
    }

    @Test
    fun displayWindowDiverged_countsAWindowTheBoardHasNotReported() {
        // This is the rule that gates the Save control. Under the null-matches rule used by the
        // settings the board can refuse, the user could pick a frame and never be able to write it.
        assertTrue(displayWindowDiverged(DisplayWindow.FULL_PANEL, null))
        assertTrue(displayWindowDiverged(GripFrame.E1.window, GripFrame.E2.window))
        assertFalse(displayWindowDiverged(null, DisplayWindow.FULL_PANEL))
        assertFalse(displayWindowDiverged(null, null))
        assertFalse(displayWindowDiverged(GripFrame.E1.window, GripFrame.E1.window))
    }

    @Test
    fun everyGripFramePresetIsARectangleTheBoardAccepts() {
        // A preset the board rejects would drop the whole write, including the other settings.
        GripFrame.entries.forEach { frame ->
            assertTrue(frame.window.isWithinPanel(), "${frame.name} is out of bounds")
        }
    }

    @Test
    fun gripFramePresetsMatchMeasuredCutouts() {
        assertEquals(DisplayWindow(x = 24, y = 0, w = 104, h = 32), GripFrame.E1.window)
        assertEquals(DisplayWindow(x = 26, y = 0, w = 102, h = 32), GripFrame.E2.window)
        assertEquals(DisplayWindow.FULL_PANEL, GripFrame.FRAME_86.window)
    }

    @Test
    fun matchingGripFrame_namesAPresetAndReportsNoneForACustomRectangle() {
        assertEquals(GripFrame.E1, GripFrame.E1.window.matchingGripFrame())
        assertEquals(GripFrame.E2, GripFrame.E2.window.matchingGripFrame())
        assertEquals(GripFrame.FRAME_86, DisplayWindow.FULL_PANEL.matchingGripFrame())
        assertNull(DisplayWindow(x = 4, y = 2, w = 100, h = 28).matchingGripFrame())
    }

    @Test
    fun matchingGripFrame_namesEveryOfferedFrame() {
        GripFrame.offered.forEach { frame ->
            assertEquals(frame, frame.window.matchingGripFrame())
        }
    }

    @Test
    fun offered_listsE1E2And86FramesInOrder() {
        assertEquals(
            listOf(GripFrame.E1, GripFrame.E2, GripFrame.FRAME_86),
            GripFrame.offered,
        )
    }
}
