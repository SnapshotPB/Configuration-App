package com.snapshotpb.configuration.common.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The PROVISIONAL sizes of the app content on the iPhone Duo displays, in dp (1 dp = 1 pt). Each
 * value is the content size after `safeDrawingPadding()`. Step 4.1 of `docs/iphone-duo-plan.md`
 * measures the real values on the simulator.
 *
 * The display sizes come from the screenshot sizes of App Store Connect at 3x. The outer display
 * is 466 x 678 pt (1398 x 2034 px). The inner display is 669 x 951 pt (2007 x 2853 px). Apple
 * gives no point sizes.
 *
 * The insets are estimates, because no public source gives them. In outer portrait, the status bar
 * and the camera are on the side (Apple HIG "Designing for iPhone Duo"). Outer portrait removes
 * 59 pt on the trailing side and 34 pt at the bottom. Outer landscape removes 59 pt on one side
 * and 21 pt at the bottom. [OUTER_LANDSCAPE_SYMMETRIC] removes 59 pt on each side, as an iPhone in
 * landscape does. That width (560 dp) is below the two-pane limit of 600 dp.
 *
 * Inner portrait removes 24 pt at the top and 20 pt at the bottom, as an iPad does. Inner
 * landscape removes 24 pt on the side and 20 pt at the bottom.
 */
internal enum class DuoSize(val label: String, val width: Dp, val height: Dp) {
    OUTER_PORTRAIT("outer-portrait", 407.dp, 644.dp),
    OUTER_LANDSCAPE("outer-landscape", 619.dp, 445.dp),
    OUTER_LANDSCAPE_SYMMETRIC("outer-landscape-symmetric", 560.dp, 445.dp),
    INNER_PORTRAIT("inner-portrait", 669.dp, 907.dp),
    INNER_LANDSCAPE("inner-landscape", 927.dp, 649.dp),
}
