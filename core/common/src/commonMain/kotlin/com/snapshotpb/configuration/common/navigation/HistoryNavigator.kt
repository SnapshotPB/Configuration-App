package com.snapshotpb.configuration.common.navigation

/**
 * Keeps a browser session history in step with the app back stack, so that the back and forward
 * buttons of the browser move between screens.
 *
 * The app back stack stays the source of truth. Each history entry holds its index in the stack as
 * its state: the root screen is entry 0. The key of a screen is not in the entry. The navigator
 * keeps the key of each index in [entries], also the keys after the top screen, so that a browser
 * forward can add a screen again.
 *
 * - When the app adds screens, the navigator pushes one entry for each. A push removes the forward
 *   entries of the browser.
 * - When the app removes screens, the navigator moves the browser back by the same count. A replaced
 *   screen keeps its entry, because the entry holds only the index.
 * - The browser reports each move later, as a pop. Only one move is in flight at a time. When the
 *   pop arrives, the navigator makes the next move if the app changed in the meantime. Thus a move
 *   that the browser drops or merges cannot put the two out of step for long.
 * - A pop that the navigator did not ask for is a browser back or forward. A pop to a lower index
 *   removes screens. A pop to a higher index adds the known forward screens again.
 *
 * [K] is the type of a screen key. The class holds no platform code, so a test can drive it with a
 * fake [History].
 */
internal class HistoryNavigator<K : Any>(
    private val history: History,
    private val backStack: MutableList<K>,
) {
    /** The part of the browser History API that the navigator uses. */
    interface History {
        /** Replaces the state of the current entry with [index]. */
        fun replace(index: Int)

        /** Adds an entry with the state [index] after the current entry. */
        fun push(index: Int)

        /** Moves [delta] entries in the history. The browser reports a pop later. */
        fun go(delta: Int)
    }

    /** The key of each index that the navigator knows, also after the top screen. */
    private val entries = mutableListOf<K>()

    /** The index of the top screen of the app. */
    private val top: Int get() = backStack.lastIndex

    /** The index of the entry that the browser shows, or the target of the move in flight. */
    private var browserIndex = -1

    /** True from a [History.go] call until the browser reports the pop. */
    private var moving = false

    /** Records the current back stack as the history. Call it one time, before the other calls. */
    fun start() {
        entries.clear()
        entries += backStack
        history.replace(0)
        for (index in 1..top) history.push(index)
        browserIndex = top
    }

    /** Brings the history in step after a change of the back stack. */
    fun onBackStackChanged() {
        val shared = entries.zip(backStack).takeWhile { (entry, key) -> entry == key }.size
        if (shared < backStack.size) {
            // Screens were added or replaced. The known keys after them no longer follow.
            entries.subList(shared, entries.size).clear()
            entries += backStack.subList(shared, backStack.size)
        }
        // When only screens were removed, the removed keys stay for a browser forward.
        if (!moving) moveBrowserToTop()
    }

    /**
     * Applies a pop to the entry with the state [index]. A negative [index] is an entry that this
     * page load did not make (for example from before a reload), and the navigator ignores it.
     */
    fun onPopState(index: Int) {
        if (index < 0) return
        browserIndex = index
        if (moving) {
            moving = false
        } else if (index < top) {
            // A browser back. Keep the root screen.
            val keep = (index + 1).coerceAtLeast(1)
            while (backStack.size > keep) backStack.removeAt(backStack.lastIndex)
        } else if (index > top) {
            // A browser forward. Add the known forward screens.
            for (entry in top + 1..minOf(index, entries.lastIndex)) backStack += entries[entry]
        }
        // The app can have changed while a move was in flight, or the browser can be at an entry
        // after the last known key.
        moveBrowserToTop()
    }

    /** Moves the browser to the entry of the top screen, or starts the move. */
    private fun moveBrowserToTop() {
        when {
            browserIndex > top -> {
                moving = true
                history.go(top - browserIndex)
                browserIndex = top
            }

            browserIndex < top -> {
                for (index in browserIndex + 1..top) history.push(index)
                browserIndex = top
            }
        }
    }
}
