package com.mpd218.training.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mpd218.training.midi.MidiManager
import com.mpd218.training.midi.PadInput
import com.mpd218.training.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class PracticeViewModel(
    private val midiManager: MidiManager
) : ViewModel() {

    private val _state = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    private var practiceJob: Job? = null
    private var expectedTimestamp: Long = 0

    init {
        observeMidiConnection()
        observePadInput()
    }

    private fun observeMidiConnection() {
        viewModelScope.launch {
            midiManager.isConnected.collect { connected ->
                _state.value = _state.value.copy(isConnected = connected)
                if (connected && _state.value.currentScreen == AppScreen.Connection) {
                    // Stay on connection screen until user taps start
                }
            }
        }
    }

    private fun observePadInput() {
        viewModelScope.launch {
            midiManager.padInput.collect { input ->
                input?.let {
                    if (_state.value.isActive) {
                        handlePadInput(it)
                    }
                    midiManager.clearPadInput()
                }
            }
        }
    }

    fun scanForDevice() {
        midiManager.scanForMPD218()
    }

    fun navigateToHome() {
        _state.value = _state.value.copy(currentScreen = AppScreen.Home)
    }

    fun navigateToModeSelect() {
        _state.value = _state.value.copy(currentScreen = AppScreen.ModeSelect)
    }

    fun navigateBack() {
        when (_state.value.currentScreen) {
            AppScreen.ModeSelect -> _state.value = _state.value.copy(currentScreen = AppScreen.Home)
            AppScreen.Result -> _state.value = _state.value.copy(currentScreen = AppScreen.ModeSelect)
            else -> {}
        }
    }

    fun selectMode(mode: PracticeMode) {
        _state.value = _state.value.copy(
            mode = mode,
            currentScreen = AppScreen.Practice
        )
        startCountdown()
    }

    private fun startCountdown() {
        practiceJob?.cancel()
        practiceJob = viewModelScope.launch {
            for (i in 3 downTo 1) {
                _state.value = _state.value.copy(countdown = i)
                delay(1000)
            }
            _state.value = _state.value.copy(countdown = null)
            startPractice()
        }
    }

    private fun startPractice() {
        val mode = _state.value.mode ?: return
        val level = _state.value.level

        when (mode) {
            PracticeMode.TIMING -> startTimingMode(level)
            PracticeMode.MEMORY -> startMemoryMode(level)
        }
    }

    private fun startTimingMode(level: Level) {
        val beatIntervalMs = (60000 / level.bpm).toLong()
        val totalBeats = 16

        val targetPads = List(totalBeats) { Random.nextInt(16) }

        _state.value = _state.value.copy(
            targetPads = targetPads,
            currentTargetIndex = 0,
            userInputs = emptyList(),
            isActive = true,
            startTime = System.currentTimeMillis()
        )

        practiceJob = viewModelScope.launch {
            expectedTimestamp = System.currentTimeMillis() + beatIntervalMs

            for (i in 0 until totalBeats) {
                _state.value = _state.value.copy(currentTargetIndex = i)
                delay(beatIntervalMs)
                expectedTimestamp += beatIntervalMs

                // Check for miss if no input received
                if (_state.value.userInputs.size <= i) {
                    recordMiss(targetPads[i])
                }
            }

            finishPractice()
        }
    }

    private fun startMemoryMode(level: Level) {
        val sequenceLength = 4 + level.ordinal  // 4-8 pads

        val targetPads = List(sequenceLength) { Random.nextInt(16) }

        _state.value = _state.value.copy(
            targetPads = targetPads,
            currentTargetIndex = 0,
            userInputs = emptyList(),
            isActive = true,
            startTime = System.currentTimeMillis()
        )

        practiceJob = viewModelScope.launch {
            // Show sequence
            for (i in targetPads.indices) {
                _state.value = _state.value.copy(currentTargetIndex = i)
                delay(800)
                _state.value = _state.value.copy(currentTargetIndex = -1)
                delay(400)
            }

            // Wait for user input
            _state.value = _state.value.copy(currentTargetIndex = 0)
        }
    }

    private fun handlePadInput(input: PadInput) {
        val currentState = _state.value
        val mode = currentState.mode ?: return

        when (mode) {
            PracticeMode.TIMING -> handleTimingInput(input)
            PracticeMode.MEMORY -> handleMemoryInput(input)
        }
    }

    private fun handleTimingInput(input: PadInput) {
        val currentState = _state.value
        val currentIndex = currentState.currentTargetIndex
        if (currentIndex >= currentState.targetPads.size) return

        val expectedPad = currentState.targetPads[currentIndex]
        val timingError = kotlin.math.abs(input.timestamp - expectedTimestamp)

        val feedback = when {
            input.padIndex != expectedPad -> HitFeedback.MISS
            timingError <= 50 -> HitFeedback.PERFECT
            timingError <= 150 -> HitFeedback.GOOD
            else -> HitFeedback.MISS
        }

        val hit = PadHit(
            padIndex = input.padIndex,
            timestamp = input.timestamp,
            timingErrorMs = timingError,
            feedback = feedback
        )

        _state.value = currentState.copy(
            userInputs = currentState.userInputs + hit
        )
    }

    private fun handleMemoryInput(input: PadInput) {
        val currentState = _state.value
        val inputIndex = currentState.userInputs.size
        if (inputIndex >= currentState.targetPads.size) return

        val expectedPad = currentState.targetPads[inputIndex]
        val reactionTime = input.timestamp - (currentState.startTime ?: 0)

        val feedback = if (input.padIndex == expectedPad) {
            if (reactionTime < 300) HitFeedback.PERFECT else HitFeedback.GOOD
        } else {
            HitFeedback.MISS
        }

        val hit = PadHit(
            padIndex = input.padIndex,
            timestamp = input.timestamp,
            feedback = feedback
        )

        val newInputs = currentState.userInputs + hit
        _state.value = currentState.copy(
            userInputs = newInputs,
            currentTargetIndex = inputIndex + 1
        )

        // Check if sequence complete
        if (newInputs.size >= currentState.targetPads.size) {
            finishPractice()
        }
    }

    private fun recordMiss(padIndex: Int) {
        val hit = PadHit(
            padIndex = padIndex,
            timestamp = System.currentTimeMillis(),
            feedback = HitFeedback.MISS
        )
        _state.value = _state.value.copy(
            userInputs = _state.value.userInputs + hit
        )
    }

    private fun finishPractice() {
        practiceJob?.cancel()
        _state.value = _state.value.copy(
            isActive = false,
            currentScreen = AppScreen.Result
        )
    }

    fun continuePractice() {
        _state.value = _state.value.copy(currentScreen = AppScreen.Practice)
        startCountdown()
    }

    override fun onCleared() {
        super.onCleared()
        practiceJob?.cancel()
        midiManager.disconnect()
    }
}
