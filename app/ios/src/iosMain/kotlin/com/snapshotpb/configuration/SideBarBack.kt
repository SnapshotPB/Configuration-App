// Swift API for the side-bar back button on iPhone Duo.
//
// This API stays small on purpose. Swift calls it directly because Kotlin/Native has no bindings
// for the iOS 27.1 vertical bar. The rule that decides when the button shows is showsBackControl
// in core:common.
package com.snapshotpb.configuration

import com.snapshotpb.configuration.common.navigation.ExternalBackControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The back control that the iOS host shows in the side bar of iPhone Duo. The process has one
 * instance. A second window uses the same instance, so all windows show the same side-bar state.
 */
internal val sideBarBackControl = ExternalBackControl()

/**
 * The state of the side-bar back button, for Swift.
 *
 * @property visible True when the host must show the back button.
 * @property label The text label for the back button.
 */
class SideBarBackState internal constructor(
    val visible: Boolean,
    val label: String,
)

/** A registration from [observeSideBarBack]. Call [cancel] to stop the calls. */
class SideBarBackObservation internal constructor(private val job: Job) {
    /** Stops the calls to the `onChange` of this registration. */
    fun cancel() {
        job.cancel()
    }
}

/**
 * Calls [onChange] with the current state of the side-bar back button, and again after each
 * change.
 *
 * From Swift, call `SideBarBackKt.observeSideBarBack { state in ... }`. Call this function on
 * the main thread only. [onChange] runs on the main thread, and the first call runs immediately.
 */
fun observeSideBarBack(onChange: (SideBarBackState) -> Unit): SideBarBackObservation {
    val job = CoroutineScope(Dispatchers.Main.immediate).launch {
        sideBarBackControl.state.collect { state ->
            onChange(SideBarBackState(visible = state.visible, label = state.label))
        }
    }
    return SideBarBackObservation(job)
}

/**
 * Asks the app to go back one screen, as the side-bar back button does.
 *
 * From Swift, call `SideBarBackKt.requestSideBarBack()`. Call this function on the main thread
 * only.
 */
fun requestSideBarBack() {
    sideBarBackControl.requestBack()
}
