@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.snapshotpb.configuration.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.random.Random

/**
 * Web actual: the browser back and forward buttons move the app back stack.
 *
 * In the 3D viewer, the app runs in a same-origin iframe, and the history of an iframe is part of
 * the session history of the viewer page. There the app does not change the history, so the browser
 * back button stays with the viewer. The floating back button of the app works in both cases.
 */
@Composable
internal actual fun BrowserHistoryEffect(backStack: NavBackStack<NavKey>) {
    val topWindow = remember { isTopWindow() }
    if (!topWindow) return
    val navigator = remember(backStack) { HistoryNavigator(WindowHistory, backStack) }
    DisposableEffect(navigator) {
        navigator.start()
        val listener = addPopStateListener { index -> navigator.onPopState(index) }
        onDispose { removePopStateListener(listener) }
    }
    LaunchedEffect(navigator) {
        snapshotFlow { backStack.toList() }.collect { navigator.onBackStackChanged() }
    }
}

/**
 * The History API of the window. The state of an entry is "<page load>:<index>", where the index is
 * the index of the screen in the back stack. The page-load token tells the entries of this page load
 * from the entries of a page load before a reload, which hold indexes of an old back stack.
 */
private object WindowHistory : HistoryNavigator.History {
    val pageLoad: String = Random.nextLong().toULong().toString(radix = 36)
    override fun replace(index: Int) = historyReplaceState("$pageLoad:$index")
    override fun push(index: Int) = historyPushState("$pageLoad:$index")
    override fun go(delta: Int) = historyGo(delta)
}

private fun isTopWindow(): Boolean = js("window.top === window.self")

private fun historyReplaceState(state: String): Unit = js("window.history.replaceState(state, '')")

private fun historyPushState(state: String): Unit = js("window.history.pushState(state, '')")

private fun historyGo(delta: Int): Unit = js("window.history.go(delta)")

/**
 * Calls [onPop] with the index of the entry that the browser moves to. An entry that this page load
 * did not make (no state, or the state of an earlier page load) gives -1, which the navigator ignores.
 */
private fun addPopStateListener(onPop: (Int) -> Unit): JsAny = addPopStateListener(WindowHistory.pageLoad, onPop)

private fun addPopStateListener(pageLoad: String, onPop: (Int) -> Unit): JsAny =
    js("""{
        const prefix = pageLoad + ':';
        const l = (e) => onPop(typeof e.state === 'string' && e.state.startsWith(prefix)
            ? parseInt(e.state.substring(prefix.length), 10) : -1);
        window.addEventListener('popstate', l);
        return l;
    }""")

private fun removePopStateListener(listener: JsAny): Unit =
    js("window.removeEventListener('popstate', listener)")
