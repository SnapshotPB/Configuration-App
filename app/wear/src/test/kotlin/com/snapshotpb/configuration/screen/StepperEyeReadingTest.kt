package com.snapshotpb.configuration.screen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_eye_hint_eye_off
import com.snapshotpb.core.resources.profile_eye_hint_fault
import com.snapshotpb.core.resources.profile_eye_hint_override_off
import com.snapshotpb.core.resources.profile_eye_hint_unsupported
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Unit tests for the live eye logic of the Wear stepper. */
@OptIn(ExperimentalCoroutinesApi::class)
class StepperEyeReadingTest {

    private lateinit var testDispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stepperWatchesEye_isTrueOnlyForEyeThresholdWithEyeMode() {
        assertTrue(
            stepperWatchesEye(
                field = MarkerProfileField.EYE_THRESHOLD,
                profile = profileWithEye(MarkerEyeMode.BREAK_BEAM),
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
        assertTrue(
            stepperWatchesEye(
                field = MarkerProfileField.EYE_THRESHOLD,
                profile = profileWithEye(MarkerEyeMode.REFLECTIVE),
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
        assertFalse(
            stepperWatchesEye(
                field = MarkerProfileField.EYE_THRESHOLD,
                profile = profileWithEye(MarkerEyeMode.OFF),
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
        assertFalse(
            stepperWatchesEye(
                field = MarkerProfileField.EYE_DEBOUNCE,
                profile = profileWithEye(MarkerEyeMode.BREAK_BEAM),
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
        assertFalse(
            stepperWatchesEye(
                field = MarkerProfileField.SEAR_SOLENOID_DWELL,
                profile = profileWithEye(MarkerEyeMode.BREAK_BEAM),
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
    }

    @Test
    fun collectEyeReadingWhileStarted_tracksLifecycleAndCancellation() = runTest(testDispatcher) {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_ID)
        manager.setEyeReading(BOARD_ID, sampleReading)
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val states = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch {
            collectEyeReadingWhileStarted(
                lifecycle = owner.lifecycle,
                eyeReading = manager.eyeReading(BOARD_ID),
                watches = true,
                onState = { states.add(it) },
            )
        }

        assertEquals(1, manager.eyeWatchers.value[BOARD_ID])
        assertEquals(sampleReading, states.last())

        owner.registry.currentState = Lifecycle.State.CREATED
        assertFalse(BOARD_ID in manager.eyeWatchers.value)
        assertNull(states.last())

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(1, manager.eyeWatchers.value[BOARD_ID])
        assertEquals(sampleReading, states.last())

        job.cancel()
        assertFalse(BOARD_ID in manager.eyeWatchers.value)
    }

    @Test
    fun collectEyeReadingWhileStarted_doesNotWatchWhenWatchesIsFalse() = runTest(testDispatcher) {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_ID)
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val states = mutableListOf<EyeReadingState?>()

        collectEyeReadingWhileStarted(
            lifecycle = owner.lifecycle,
            eyeReading = manager.eyeReading(BOARD_ID),
            watches = false,
            onState = { states.add(it) },
        )

        assertFalse(BOARD_ID in manager.eyeWatchers.value)
        assertEquals(listOf<EyeReadingState?>(null), states)
    }

    @Test
    fun collectEyeReadingWhileStarted_doesNotWatchWhenCreatedFromStart() = runTest(testDispatcher) {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_ID)
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val states = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch {
            collectEyeReadingWhileStarted(
                lifecycle = owner.lifecycle,
                eyeReading = manager.eyeReading(BOARD_ID),
                watches = true,
                onState = { states.add(it) },
            )
        }

        assertFalse(BOARD_ID in manager.eyeWatchers.value)
        assertTrue(states.isEmpty())

        job.cancel()
    }

    @Test
    fun collectEyeReadingWhileStarted_deliversRecordedStateToOnState() = runTest(testDispatcher) {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_ID)
        val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val states = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch {
            collectEyeReadingWhileStarted(
                lifecycle = owner.lifecycle,
                eyeReading = manager.eyeReading(BOARD_ID),
                watches = true,
                onState = { states.add(it) },
            )
        }

        manager.setEyeReading(BOARD_ID, sampleReading)
        assertEquals(sampleReading, states.last())

        job.cancel()
    }

    @Test
    fun stepperEyeLine_nullState_isNull() {
        assertNull(stepperEyeLine(null, MarkerEyeMode.BREAK_BEAM, 50))
    }

    @Test
    fun stepperEyeLine_waiting_isNull() {
        assertNull(stepperEyeLine(EyeReadingState.Waiting, MarkerEyeMode.BREAK_BEAM, 50))
    }

    @Test
    fun stepperEyeLine_failed_isNull() {
        assertNull(stepperEyeLine(EyeReadingState.Failed, MarkerEyeMode.BREAK_BEAM, 50))
    }

    @Test
    fun stepperEyeLine_notPermitted_isNull() {
        assertNull(stepperEyeLine(EyeReadingState.NotPermitted, MarkerEyeMode.BREAK_BEAM, 50))
    }

    @Test
    fun stepperEyeLine_unsupported_givesHint() {
        assertEquals(
            StepperEyeLine.Hint(Res.string.profile_eye_hint_unsupported),
            stepperEyeLine(EyeReadingState.Unsupported, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_activeReading_formatsPercentAndAppliesBallRule() {
        assertEquals(
            StepperEyeLine.Level("42.3", seesBall = true),
            stepperEyeLine(sampleReading, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            StepperEyeLine.Level("42.3", seesBall = false),
            stepperEyeLine(sampleReading, MarkerEyeMode.REFLECTIVE, 50),
        )
    }

    @Test
    fun stepperEyeLine_activeReading_formatsScaleEnds() {
        val zero = activeReading(levelRaw = 0)
        val full = activeReading(levelRaw = EYE_ADC_FULL_SCALE_RAW)

        assertEquals(
            StepperEyeLine.Level("0.0", seesBall = true),
            stepperEyeLine(zero, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            StepperEyeLine.Level("100.0", seesBall = false),
            stepperEyeLine(full, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_activeReading_appliesThresholdEdge() {
        val atThreshold = activeReading(levelRaw = 2047)
        val aboveThreshold = activeReading(levelRaw = 2048)

        assertEquals(
            StepperEyeLine.Level("50.0", seesBall = true),
            stepperEyeLine(atThreshold, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            StepperEyeLine.Level("50.0", seesBall = false),
            stepperEyeLine(aboveThreshold, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_activeReading_usesEditedThreshold() {
        val level = activeReading(levelRaw = 2500)

        assertEquals(
            StepperEyeLine.Level("61.1", seesBall = false),
            stepperEyeLine(level, MarkerEyeMode.BREAK_BEAM, 50),
        )
        assertEquals(
            StepperEyeLine.Level("61.1", seesBall = true),
            stepperEyeLine(level, MarkerEyeMode.BREAK_BEAM, 70),
        )
    }

    @Test
    fun stepperEyeLine_activeReading_unknownEyeModeGivesNullBallResult() {
        assertEquals(
            StepperEyeLine.Level("42.3", seesBall = null),
            stepperEyeLine(sampleReading, MarkerEyeMode.UNKNOWN, 50),
        )
    }

    @Test
    fun stepperEyeLine_activeWithNoLevel_isNull() {
        val noLevel = EyeReadingState.Reading(
            EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.ACTIVE),
        )
        val zeroScale = EyeReadingState.Reading(
            EyeReading(levelRaw = 1733, fullScaleRaw = 0, state = EyeSensorState.ACTIVE),
        )

        assertNull(stepperEyeLine(noLevel, MarkerEyeMode.BREAK_BEAM, 50))
        assertNull(stepperEyeLine(zeroScale, MarkerEyeMode.BREAK_BEAM, 50))
    }

    @Test
    fun stepperEyeLine_eyeOff_givesHint() {
        val state = EyeReadingState.Reading(
            EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.EYE_OFF),
        )
        assertEquals(
            StepperEyeLine.Hint(Res.string.profile_eye_hint_eye_off),
            stepperEyeLine(state, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_overrideOff_givesHint() {
        val state = EyeReadingState.Reading(
            EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.OVERRIDE_OFF),
        )
        assertEquals(
            StepperEyeLine.Hint(Res.string.profile_eye_hint_override_off),
            stepperEyeLine(state, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_fault_givesHint() {
        val state = EyeReadingState.Reading(
            EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.FAULT),
        )
        assertEquals(
            StepperEyeLine.Hint(Res.string.profile_eye_hint_fault),
            stepperEyeLine(state, MarkerEyeMode.BREAK_BEAM, 50),
        )
    }

    @Test
    fun stepperEyeLine_unknownSensorState_isNull() {
        val state = EyeReadingState.Reading(
            EyeReading(levelRaw = null, fullScaleRaw = EYE_ADC_FULL_SCALE_RAW, state = EyeSensorState.UNKNOWN),
        )
        assertNull(stepperEyeLine(state, MarkerEyeMode.BREAK_BEAM, 50))
    }

    private fun activeReading(levelRaw: Int): EyeReadingState.Reading = EyeReadingState.Reading(
        EyeReading(
            levelRaw = levelRaw,
            fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
            state = EyeSensorState.ACTIVE,
        ),
    )

    private fun profileWithEye(eyeMode: MarkerEyeMode): MarkerProfile = MarkerProfile(
        id = PROFILE_ID,
        name = "Test",
        eyeMode = eyeMode,
    )

    private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = initialState
        }

        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val BOARD_ID = "board-1"
        val PROFILE_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val sampleReading = EyeReadingState.Reading(
            EyeReading(
                levelRaw = 1733,
                fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
                state = EyeSensorState.ACTIVE,
            ),
        )
    }
}
