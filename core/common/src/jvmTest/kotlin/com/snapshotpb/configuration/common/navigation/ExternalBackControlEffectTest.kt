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
        waitForIdle()
        assertEquals(emptyList(), calls)

        // When visible becomes true and label changes, state follows and requestBack calls onBack.
        runOnIdle {
            visible = true
            label = "Back to devices"
        }
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = true, label = "Back to devices"),
            control.state.value,
        )

        runOnIdle { control.requestBack() }
        waitForIdle()
        assertEquals(listOf("first"), calls)

        // After onBack changes, requestBack calls the new lambda.
        runOnIdle { onBack = { calls += "second" } }
        waitForIdle()
        runOnIdle { control.requestBack() }
        waitForIdle()
        assertEquals(listOf("first", "second"), calls)

        // When visible returns to false, requestBack does not call onBack.
        runOnIdle { visible = false }
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = false, label = "Back to devices"),
            control.state.value,
        )

        runOnIdle { control.requestBack() }
        waitForIdle()
        assertEquals(listOf("first", "second"), calls)

        // Show the control again before the effect leaves the composition.
        runOnIdle { visible = true }
        waitForIdle()
        assertEquals(
            ExternalBackState(visible = true, label = "Back to devices"),
            control.state.value,
        )

        // When the effect leaves the composition, state resets and requestBack does nothing.
        runOnIdle { shown = false }
        waitForIdle()
        assertEquals(ExternalBackState(), control.state.value)

        runOnIdle { control.requestBack() }
        waitForIdle()
        assertEquals(listOf("first", "second"), calls)
    }
}
