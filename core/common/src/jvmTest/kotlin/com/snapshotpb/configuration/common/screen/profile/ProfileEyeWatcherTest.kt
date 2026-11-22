package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Screen watcher tests for [collectProfileEyeReading]. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProfileEyeWatcherTest {

    @BeforeTest
    fun setUp() {
        // collectAsStateWithLifecycle uses repeatOnLifecycle, which runs on Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val deviceId = testDeviceId.toString()
    private val sampleReading = EyeReadingState.Reading(
        EyeReading(
            levelRaw = 1000,
            fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
            state = EyeSensorState.ACTIVE,
        ),
    )

    private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle get() = registry

        var state: Lifecycle.State
            get() = registry.currentState
            set(value) {
                registry.currentState = value
            }
    }

    private fun connectedManager(): BluetoothStateManager = BluetoothStateManager().apply {
        deviceConnected(deviceId)
        setEyeReading(deviceId, sampleReading)
    }

    @Test
    fun isAWatcherWhileStartedAndTheProfileUsesTheEye() = runSkikoComposeUiTest {
        val manager = connectedManager()
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val profile = testProfile().copy(eyeMode = MarkerEyeMode.BREAK_BEAM)

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = collectProfileEyeReading(profile, MarkerBoltMode.CLOSED) {
                    manager.eyeReading(deviceId)
                }
                Text(
                    text = state?.toString() ?: NULL_LABEL,
                    modifier = Modifier.testTag(READING_TAG),
                )
            }
        }
        waitForIdle()

        assertEquals(1, manager.eyeWatchers.value[deviceId])
        onNodeWithTag(READING_TAG).assertTextEquals(sampleReading.toString())
    }

    @Test
    fun isNoWatcherInTheBackground() = runSkikoComposeUiTest {
        val manager = connectedManager()
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val profile = testProfile().copy(eyeMode = MarkerEyeMode.BREAK_BEAM)

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = collectProfileEyeReading(profile, MarkerBoltMode.CLOSED) {
                    manager.eyeReading(deviceId)
                }
                Text(
                    text = state?.toString() ?: NULL_LABEL,
                    modifier = Modifier.testTag(READING_TAG),
                )
            }
        }
        waitForIdle()
        assertEquals(1, manager.eyeWatchers.value[deviceId])

        runOnIdle { owner.state = Lifecycle.State.CREATED }
        waitForIdle()
        assertFalse(deviceId in manager.eyeWatchers.value)

        runOnIdle { owner.state = Lifecycle.State.STARTED }
        waitForIdle()
        assertEquals(1, manager.eyeWatchers.value[deviceId])
    }

    @Test
    fun isNoWatcherBeforeTheScreenStarts() = runSkikoComposeUiTest {
        val manager = connectedManager()
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val profile = testProfile().copy(eyeMode = MarkerEyeMode.BREAK_BEAM)

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = collectProfileEyeReading(profile, MarkerBoltMode.CLOSED) {
                    manager.eyeReading(deviceId)
                }
                Text(
                    text = state?.toString() ?: NULL_LABEL,
                    modifier = Modifier.testTag(READING_TAG),
                )
            }
        }
        waitForIdle()

        assertFalse(deviceId in manager.eyeWatchers.value)
        onNodeWithTag(READING_TAG).assertTextEquals(NULL_LABEL)
    }

    @Test
    fun isNoWatcherForAProfileWithNoEye() = runSkikoComposeUiTest {
        val manager = connectedManager()
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        var profile by mutableStateOf(testProfile().copy(eyeMode = MarkerEyeMode.OFF))

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val state = collectProfileEyeReading(profile, MarkerBoltMode.CLOSED) {
                    manager.eyeReading(deviceId)
                }
                Text(
                    text = state?.toString() ?: NULL_LABEL,
                    modifier = Modifier.testTag(READING_TAG),
                )
            }
        }
        waitForIdle()
        assertFalse(deviceId in manager.eyeWatchers.value)
        onNodeWithTag(READING_TAG).assertTextEquals(NULL_LABEL)

        runOnIdle { profile = profile.copy(eyeMode = MarkerEyeMode.REFLECTIVE) }
        waitForIdle()
        assertEquals(1, manager.eyeWatchers.value[deviceId])
        onNodeWithTag(READING_TAG).assertTextEquals(sampleReading.toString())

        runOnIdle { profile = profile.copy(eyeMode = MarkerEyeMode.OFF) }
        waitForIdle()
        assertFalse(deviceId in manager.eyeWatchers.value)
        onNodeWithTag(READING_TAG).assertTextEquals(NULL_LABEL)
    }

    @Test
    fun isNoWatcherAfterTheScreenLeaves() = runSkikoComposeUiTest {
        val manager = connectedManager()
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val profile = testProfile().copy(eyeMode = MarkerEyeMode.BREAK_BEAM)
        var shown by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) {
                    val state = collectProfileEyeReading(profile, MarkerBoltMode.CLOSED) {
                        manager.eyeReading(deviceId)
                    }
                    Text(
                        text = state?.toString() ?: NULL_LABEL,
                        modifier = Modifier.testTag(READING_TAG),
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(1, manager.eyeWatchers.value[deviceId])

        runOnIdle { shown = false }
        waitForIdle()
        assertFalse(deviceId in manager.eyeWatchers.value)
    }

    private companion object {
        const val READING_TAG = "eyeReadingValue"
        const val NULL_LABEL = "null"
    }
}
