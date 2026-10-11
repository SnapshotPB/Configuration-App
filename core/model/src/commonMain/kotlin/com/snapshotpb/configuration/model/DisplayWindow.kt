package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * Panel extent along its long axis, in panel pixels. The panel is fitted turned 90 degrees, so the
 * user sees this run vertically on the marker.
 */
const val DISPLAY_PANEL_WIDTH = 128

/** Panel extent along its short axis, in panel pixels. The user sees this run horizontally. */
const val DISPLAY_PANEL_HEIGHT = 32

/** Smallest window the board accepts along the panel's long axis. Below this the UI is unreadable. */
const val DISPLAY_WINDOW_MIN_WIDTH = 70

/** Smallest window the board accepts along the panel's short axis. */
const val DISPLAY_WINDOW_MIN_HEIGHT = 26

/**
 * The part of the board's display that the marker's grip frame reveals.
 *
 * The board drives the whole panel, and the grip frame hides part of it in both axes. This
 * rectangle tells the board where to lay its own UI out. The coordinates are the panel's own, in
 * pixels, with the origin at its 0,0.
 *
 * MIND THE 90 DEGREE MOUNT when you MEASURE a frame. The panel is [DISPLAY_PANEL_WIDTH] by
 * [DISPLAY_PANEL_HEIGHT] in its own frame, but the marker holds it turned, so [w] is how much of
 * the user's vertical run the frame reveals and [h] is how much of the horizontal width. Map a
 * measurement taken on the glass through that rotation once, before it becomes a rectangle here.
 * Every value in this class, on the wire, and in the app's drawing is in the panel's OWN frame.
 *
 * THE BOARD REJECTS THE WHOLE MESSAGE when the rectangle is out of bounds. This is unlike
 * [BoardLanguage] and [BoardScreenBrightness], which the board ignores one field at a time. So the
 * app clamps with [clampedToPanel] at the write boundary, and the value sent, the value cached as
 * the baseline, and the editable value all agree.
 *
 * A legal rectangle in the wrong PLACE shows the user nothing at all, and the on-screen menu is one
 * of the ways they would correct it. So the UI always offers [FULL_PANEL] as a way back to the
 * whole panel, and the board's own `Setup > Full window` item does the same on the board.
 */
@Serializable
data class DisplayWindow(
    /** Top-left corner along the panel's long axis. */
    val x: Int = 0,
    /** Top-left corner along the panel's short axis. */
    val y: Int = 0,
    /** Extent along the panel's long axis, [DISPLAY_WINDOW_MIN_WIDTH] to [DISPLAY_PANEL_WIDTH]. */
    val w: Int = DISPLAY_PANEL_WIDTH,
    /** Extent along the panel's short axis, [DISPLAY_WINDOW_MIN_HEIGHT] to [DISPLAY_PANEL_HEIGHT]. */
    val h: Int = DISPLAY_PANEL_HEIGHT,
) {
    /**
     * Bounds this rectangle to the board's limits: the extents come first, then the corner moves so
     * that the whole rectangle stays on the panel.
     */
    fun clampedToPanel(): DisplayWindow {
        val width = w.coerceIn(DISPLAY_WINDOW_MIN_WIDTH, DISPLAY_PANEL_WIDTH)
        val height = h.coerceIn(DISPLAY_WINDOW_MIN_HEIGHT, DISPLAY_PANEL_HEIGHT)
        return DisplayWindow(
            x = x.coerceIn(0, DISPLAY_PANEL_WIDTH - width),
            y = y.coerceIn(0, DISPLAY_PANEL_HEIGHT - height),
            w = width,
            h = height,
        )
    }

    /** Whether the board accepts this rectangle as it stands. */
    fun isWithinPanel(): Boolean = this == clampedToPanel()

    companion object {
        /** The whole panel. The way back when a window hides the board's UI. */
        val FULL_PANEL = DisplayWindow(
            x = 0,
            y = 0,
            w = DISPLAY_PANEL_WIDTH,
            h = DISPLAY_PANEL_HEIGHT,
        )
    }
}

/**
 * Whether a local rectangle is an unsaved change against the one the board last reported.
 *
 * This is deliberately NOT [boardValuesMatch]. The three settings that use that rule are ones the
 * board can refuse one at a time, so the app cannot force agreement and a `null` on either side has
 * to count as a match. The board takes a legal display window or rejects the whole message, so the
 * app CAN force agreement here, and the rule is asymmetric instead:
 *
 * - The app holds no rectangle: nothing to push, so nothing has diverged. The first read that
 *   carries one is adopted.
 * - The app holds one and the board reported none: this IS a divergence. A board reports none only
 *   when its firmware predates the field, or when a read was malformed — and a malformed read is
 *   exactly the broken window the user needs to correct. Under the `null`-matches rule the user
 *   could pick a frame, see no unsaved badge, and never reach the control that writes it.
 *
 * A write settles the baseline at once, and a board that goes on reporting none clears the stored
 * value on the next read, so no badge can persist.
 */
fun displayWindowDiverged(local: DisplayWindow?, baseline: DisplayWindow?): Boolean =
    local != null && local != baseline

/**
 * The grip frames the app knows as a ready-made display area.
 *
 * This enum is deliberately not `@Serializable` and is never persisted: it is a lookup table of
 * rectangles, and only the chosen [DisplayWindow] is stored. So a later edit to a rectangle here
 * reaches every device at once, and a frame that is dropped cannot break a stored device.
 *
 * The board firmware knows no frame by name, and a frame it has never heard of must still work. So
 * these are a convenience in the app only: each one names a rectangle, and the user picks a custom
 * rectangle when no entry fits.
 *
 * Only a frame with [measured] set is a preset: the picker offers it ([offered]), and
 * [matchingGripFrame] names it. A frame without [measured] stays in the enum, but the app does not
 * offer or name it, because its rectangle is not correct for the frame.
 *
 * TODO: [E1] and [E2] hold PLACEHOLDER rectangles, so [measured] is false for both. Measure the
 * cutout of each frame on the glass, map it through the 90 degree mount, replace the numbers below,
 * and set [measured] to true.
 */
enum class GripFrame(val window: DisplayWindow, val measured: Boolean) {
    /** The whole panel. Correct for a frame that hides nothing, and the way back from a bad window. */
    FULL_PANEL(DisplayWindow.FULL_PANEL, measured = true),

    /** PLACEHOLDER rectangle for the E1 frame. Not measured, so the app hides it. */
    E1(DisplayWindow(x = 0, y = 3, w = 128, h = 26), measured = false),

    /** PLACEHOLDER rectangle for the E2 frame. Not measured, so the app hides it. */
    E2(DisplayWindow(x = 9, y = 3, w = 110, h = 26), measured = false),
    ;

    companion object {
        /**
         * The frames the picker offers, in the order of the enum, so [FULL_PANEL] is first. Only a
         * [measured] frame is in the list.
         */
        val offered: List<GripFrame> = entries.filter { it.measured }
    }
}

/**
 * The offered frame that names this rectangle, or `null` when the user set a custom one.
 *
 * A frame that is not [GripFrame.measured] never matches. A device that stored the placeholder
 * rectangle of such a frame thus shows it as a custom rectangle, with the numbers in the editor.
 */
fun DisplayWindow.matchingGripFrame(): GripFrame? =
    GripFrame.offered.firstOrNull { it.window == this }
