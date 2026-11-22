package com.snapshotpb.configuration.common.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for [showsBackControl] and [ExternalBackControl]. */
class ExternalBackControlTest {

    @Test
    fun showsBackControl_returnsFalseForNullAndDeviceBrowser() {
        assertFalse(showsBackControl(null))
        assertFalse(showsBackControl(ScreenKeyDeviceBrowser))
    }

    @Test
    fun showsBackControl_returnsTrueForOtherScreens() {
        assertTrue(showsBackControl(ScreenKeyProfiles(deviceId = "device-1")))
        assertTrue(showsBackControl(ScreenKeyDeviceConfigure(deviceId = "device-1")))
        assertTrue(showsBackControl(ScreenKeyBluetoothLog))
    }

    @Test
    fun newControl_startsHiddenWithEmptyLabel() {
        val control = ExternalBackControl()

        assertEquals(ExternalBackState(visible = false, label = ""), control.state.value)
    }

    @Test
    fun requestBack_callsHandlerOnceWhenAttachedAndVisible() {
        val control = ExternalBackControl()
        var calls = 0
        control.attach { calls += 1 }
        control.update(visible = true, label = "Back")

        assertEquals(ExternalBackState(visible = true, label = "Back"), control.state.value)
        control.requestBack()
        assertEquals(1, calls)
    }

    @Test
    fun requestBack_doesNotCallHandlerWhenHidden() {
        val control = ExternalBackControl()
        var calls = 0
        control.attach { calls += 1 }
        control.update(visible = true, label = "Back")
        control.update(visible = false, label = "Back")

        assertEquals(ExternalBackState(visible = false, label = "Back"), control.state.value)
        control.requestBack()
        assertEquals(0, calls)
    }

    @Test
    fun detach_resetsStateAndClearsHandler() {
        val control = ExternalBackControl()
        var calls = 0
        val handler: () -> Unit = { calls += 1 }
        control.attach(handler)
        control.update(visible = true, label = "Back")

        control.detach(handler)

        assertEquals(ExternalBackState(), control.state.value)
        control.requestBack()
        control.update(visible = true, label = "Back")
        control.requestBack()
        assertEquals(0, calls)
    }

    @Test
    fun detach_ignoresOldHandlerAfterNewerAttach() {
        val control = ExternalBackControl()
        var callsA = 0
        var callsB = 0
        val handlerA: () -> Unit = { callsA += 1 }
        val handlerB: () -> Unit = { callsB += 1 }
        control.attach(handlerA)
        control.attach(handlerB)
        control.update(visible = true, label = "Back")

        control.detach(handlerA)

        assertEquals(ExternalBackState(visible = true, label = "Back"), control.state.value)
        control.requestBack()
        assertEquals(0, callsA)
        assertEquals(1, callsB)
    }
}
