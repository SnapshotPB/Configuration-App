package com.snapshotpb.configuration.common.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Composition tests for [ExternalBackControlEffect]. */
@OptIn(ExperimentalTestApi::class)
class ExternalBackControlEffectTest {

    @Test
    fun stateFollowsInputsAndBackHandlerUpdatesAndDetaches() = runComposeUiTest {
        val control = ExternalBackControl()
        var shown by mutableStateOf(true)
        var visible by mutableStateOf(false)
        var label by mutableStateOf("Back")
        val calls = mutableListOf<String>()
        var onBack by mutableStateOf({ calls += "first" })

        setContent {
            if (shown) {
                ExternalBackControlEffect(
                    control = control,
                    visible = visible,
                    label = label,
                    onBack = onBack,
                )
            }
        }

        waitForIdle()
        assertEquals(ExternalBackState(visible = false, label = "Back"), control.state.value)

        // While visible is false, requestBack does not call onBack.
        runOnIdle { control.requestBack() }
        assertEquals(emptyList(), calls)

        // When visible becomes true and label changes, state follows and requestBack calls onBack.
        visible = true
        label = "Back to devices"
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = true, label = "Back to devices"),
            control.state.value,
        )

        runOnIdle { control.requestBack() }
        assertEquals(listOf("first"), calls)

        // After onBack changes, requestBack calls the new lambda.
        onBack = { calls += "second" }
        waitForIdle()
        runOnIdle { control.requestBack() }
        assertEquals(listOf("first", "second"), calls)

        // When visible returns to false, requestBack does not call onBack.
        visible = false
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = false, label = "Back to devices"),
            control.state.value,
        )

        runOnIdle { control.requestBack() }
        assertEquals(listOf("first", "second"), calls)

        // Show the control again before the effect leaves the composition.
        visible = true
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = true, label = "Back to devices"),
            control.state.value,
        )

        // When the effect leaves the composition, state resets and requestBack does nothing.
        shown = false
        waitForIdle()
        assertEquals(ExternalBackState(), control.state.value)

        runOnIdle { control.requestBack() }
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun lateDisposeOfFirstEffect_keepsSecondHandlerAndState() = runComposeUiTest {
        val control = ExternalBackControl()
        var shown1 by mutableStateOf(true)
        var shown2 by mutableStateOf(false)
        var calls1 = 0
        var calls2 = 0

        setContent {
            if (shown1) {
                ExternalBackControlEffect(
                    control = control,
                    visible = true,
                    label = "Back 1",
                    onBack = { calls1 += 1 },
                )
            }
            if (shown2) {
                ExternalBackControlEffect(
                    control = control,
                    visible = true,
                    label = "Back 2",
                    onBack = { calls2 += 1 },
                )
            }
        }

        waitForIdle()
        assertEquals(ExternalBackState(visible = true, label = "Back 1"), control.state.value)

        // The second effect enters the composition while the first is still attached.
        shown2 = true
        waitForIdle()
        assertEquals(ExternalBackState(visible = true, label = "Back 2"), control.state.value)

        // When the first effect leaves the composition, the second handler and state must remain.
        shown1 = false
        waitForIdle()
        assertEquals(ExternalBackState(visible = true, label = "Back 2"), control.state.value)

        runOnIdle { control.requestBack() }
        assertEquals(0, calls1)
        assertEquals(1, calls2)
    }
}
