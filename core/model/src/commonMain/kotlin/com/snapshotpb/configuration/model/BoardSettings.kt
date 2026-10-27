package com.snapshotpb.configuration.model

/**
 * Whether two values of one board-reported setting agree, for the divergence and conflict checks.
 *
 * `null` means that there is no value to compare: a device or baseline stored before the app read
 * the setting, or a board that reports none. A board reports none when its firmware predates the
 * field, or when it holds a value that this app does not know. A `null` side matches any value, so
 * a missing value never shows as an unsaved change, and never raises a conflict that no write can
 * clear.
 *
 * The board settings that need this are the ones the board can refuse: [BoardLanguage],
 * [BoardScreenBrightness], the screen-off delay, and [MarkerBoltMode]. Each of them sends its unset
 * value to leave the board's own value alone, so the app cannot force agreement when one side has
 * nothing.
 *
 * [DisplayWindow] is NOT one of them, although it too is absent when unset. The board takes a legal
 * rectangle or rejects the whole message, so the app can force agreement, and the window uses the
 * asymmetric [displayWindowDiverged] rule instead. See its documentation for why.
 */
fun <T> boardValuesMatch(first: T?, second: T?): Boolean =
    first == null || second == null || first == second
