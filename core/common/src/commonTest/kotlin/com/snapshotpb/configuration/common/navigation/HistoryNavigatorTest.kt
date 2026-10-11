package com.snapshotpb.configuration.common.navigation

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryNavigatorTest {

    /** Records the History API calls, as the browser gets them. */
    private class FakeHistory : HistoryNavigator.History {
        val calls = mutableListOf<String>()
        override fun replace(index: Int) { calls += "replace($index)" }
        override fun push(index: Int) { calls += "push($index)" }
        override fun go(delta: Int) { calls += "go($delta)" }
    }

    private lateinit var history: FakeHistory
    private lateinit var backStack: MutableList<String>
    private lateinit var navigator: HistoryNavigator<String>

    @BeforeTest
    fun setUp() {
        history = FakeHistory()
        backStack = mutableListOf("devices")
        navigator = HistoryNavigator(history, backStack)
        navigator.start()
        history.calls.clear()
    }

    private fun navigate(vararg keys: String) {
        backStack += keys
        navigator.onBackStackChanged()
    }

    @Test
    fun start_marksTheRootAndPushesTheScreensThatAreAlreadyOpen() {
        val fresh = FakeHistory()
        HistoryNavigator(fresh, mutableListOf("devices", "profiles")).start()
        assertEquals(listOf("replace(0)", "push(1)"), fresh.calls)
    }

    @Test
    fun aNewScreen_pushesOneEntry() {
        navigate("profiles")
        navigate("profile")
        assertEquals(listOf("push(1)", "push(2)"), history.calls)
    }

    @Test
    fun browserBack_removesTheTopScreen() {
        navigate("profiles", "profile")
        navigator.onPopState(1)
        navigator.onBackStackChanged()
        assertEquals(listOf("devices", "profiles"), backStack)
        assertEquals(listOf("push(1)", "push(2)"), history.calls)
    }

    @Test
    fun browserBackTwoEntries_removesTwoScreens() {
        navigate("profiles", "profile")
        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }

    @Test
    fun browserForward_addsTheScreenAgain() {
        navigate("profiles", "profile")
        navigator.onPopState(1)
        navigator.onBackStackChanged()
        navigator.onPopState(2)
        navigator.onBackStackChanged()
        assertEquals(listOf("devices", "profiles", "profile"), backStack)
        assertEquals(listOf("push(1)", "push(2)"), history.calls)
    }

    @Test
    fun appBack_movesTheBrowserBackAndIgnoresTheReportedPop() {
        navigate("profiles", "profile")
        backStack.removeAt(backStack.lastIndex)
        navigator.onBackStackChanged()
        assertEquals("go(-1)", history.calls.last())

        navigator.onPopState(1)
        assertEquals(listOf("devices", "profiles"), backStack)
    }

    @Test
    fun browserForwardAfterAnAppBack_addsTheScreenAgain() {
        navigate("profiles", "profile")
        backStack.removeAt(backStack.lastIndex)
        navigator.onBackStackChanged()
        navigator.onPopState(1)

        navigator.onPopState(2)
        assertEquals(listOf("devices", "profiles", "profile"), backStack)
    }

    @Test
    fun aNewScreenAfterABack_dropsTheForwardEntries() {
        navigate("profiles", "profile")
        navigator.onPopState(1)
        navigator.onBackStackChanged()
        navigate("configure")

        navigator.onPopState(3)
        assertEquals(listOf("devices", "profiles", "configure"), backStack)
    }

    @Test
    fun aReplacedTopScreen_keepsItsEntry() {
        navigate("connecting")
        backStack[backStack.lastIndex] = "configure"
        navigator.onBackStackChanged()
        // The entry holds only the index, so the replaced screen needs no history call.
        assertEquals(listOf("push(1)"), history.calls)

        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }

    @Test
    fun aShorterReplacement_movesTheBrowserBackToTheNewTop() {
        navigate("profiles", "profile")
        backStack.removeAt(2)
        backStack[1] = "configure"
        navigator.onBackStackChanged()
        assertEquals("go(-1)", history.calls.last())

        navigator.onPopState(1)
        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }

    @Test
    fun aBrowserForwardAfterAReplacement_doesNotAddTheOldScreen() {
        navigate("profiles", "profile")
        backStack.removeAt(2)
        backStack[1] = "configure"
        navigator.onBackStackChanged()
        navigator.onPopState(1)
        history.calls.clear()

        navigator.onPopState(2)
        assertEquals(listOf("devices", "configure"), backStack)
        assertEquals(listOf("go(-1)"), history.calls)
    }

    @Test
    fun twoAppBacksInTwoFrames_makeOneMoveAtATime() {
        navigate("profiles", "profile")
        history.calls.clear()
        backStack.removeAt(2)
        navigator.onBackStackChanged()
        backStack.removeAt(1)
        navigator.onBackStackChanged()
        assertEquals(listOf("go(-1)"), history.calls)

        navigator.onPopState(1)
        assertEquals(listOf("go(-1)", "go(-1)"), history.calls)
        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }

    @Test
    fun twoAppBacksThatTheBrowserMerges_needNoMoreMoves() {
        navigate("profiles", "profile")
        history.calls.clear()
        backStack.removeAt(2)
        navigator.onBackStackChanged()
        backStack.removeAt(1)
        navigator.onBackStackChanged()

        // The browser reports only the end of both moves.
        navigator.onPopState(0)
        assertEquals(listOf("go(-1)"), history.calls)
        assertEquals(listOf("devices"), backStack)

        // The next browser back is a user action again.
        navigate("profiles")
        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }

    @Test
    fun aNewScreenWhileAMoveIsInFlight_isPushedAfterThePop() {
        navigate("profiles", "profile")
        history.calls.clear()
        backStack.removeAt(2)
        navigator.onBackStackChanged()
        navigate("configure")
        assertEquals(listOf("go(-1)"), history.calls)

        navigator.onPopState(1)
        assertEquals(listOf("go(-1)", "push(2)"), history.calls)
        assertEquals(listOf("devices", "profiles", "configure"), backStack)
    }

    @Test
    fun aPopFromAnotherPageLoad_isIgnored() {
        navigate("profiles")
        navigator.onPopState(-1)
        assertEquals(listOf("devices", "profiles"), backStack)
    }

    @Test
    fun browserBackAtTheRoot_keepsTheRootScreen() {
        navigator.onPopState(0)
        assertEquals(listOf("devices"), backStack)
    }
}
