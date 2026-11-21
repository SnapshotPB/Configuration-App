package com.snapshotpb.configuration.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Returns true when a back control must show on [currentScreen].
 *
 * This rule applies to the back button on desktop and web, and to the side-bar back button on
 * iPhone Duo. It returns true for each screen except [ScreenKeyDeviceBrowser].
 */
fun showsBackControl(currentScreen: NavKey?): Boolean =
    currentScreen != null && currentScreen != ScreenKeyDeviceBrowser

/**
 * The state that a back control outside Compose shows.
 *
 * @property visible True when the host must show the back control.
 * @property label The text label for the back control.
 */
data class ExternalBackState(
    val visible: Boolean = false,
    val label: String = "",
)

/**
 * Connects the navigation of `App` to a back control outside Compose. For example, iPhone Duo
 * draws this back button in its side bar.
 *
 * `App` keeps [state] in step with its back stack through [ExternalBackControlEffect]. A platform
 * host reads [state] to show or hide the back control and to set its label. When the user selects
 * that control, the host calls [requestBack] to move `App` back one screen.
 *
 * Call [requestBack], [attach], [update], and [detach] on the main thread only. This class uses no
 * locks.
 */
class ExternalBackControl {
    private val _state = MutableStateFlow(ExternalBackState())

    /** The current visibility and label of the external back control. */
    val state: StateFlow<ExternalBackState> = _state.asStateFlow()

    /** The attached back handler. Read and write this field on the main thread only. */
    private var onBack: (() -> Unit)? = null

    /**
     * Asks the attached `App` to go back one screen.
     *
     * This call does nothing when [state] has `visible` set to false, or when no `App` is attached.
     * Call this function on the main thread only.
     */
    fun requestBack() {
        if (!_state.value.visible) return
        onBack?.invoke()
    }

    /** Registers [onBack] as the handler for [requestBack]. Call on the main thread only. */
    internal fun attach(onBack: () -> Unit) {
        this.onBack = onBack
    }

    /** Sets [state] to [visible] and [label]. Call on the main thread only. */
    internal fun update(visible: Boolean, label: String) {
        _state.value = ExternalBackState(visible = visible, label = label)
    }

    /** Clears the back handler and resets [state] to default values. Call on the main thread only. */
    internal fun detach() {
        onBack = null
        _state.value = ExternalBackState()
    }
}

/**
 * Keeps [control] in step with the current screen.
 *
 * This effect attaches [onBack] while it is in the composition and updates [control] with
 * [visible] and [label] after each recomposition. When it leaves the composition, it detaches
 * [control].
 */
@Composable
internal fun ExternalBackControlEffect(
    control: ExternalBackControl,
    visible: Boolean,
    label: String,
    onBack: () -> Unit,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(control) {
        control.attach { currentOnBack() }
        onDispose { control.detach() }
    }
    SideEffect {
        control.update(visible = visible, label = label)
    }
}
