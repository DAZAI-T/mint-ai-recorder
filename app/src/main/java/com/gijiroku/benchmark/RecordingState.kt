package com.gijiroku.benchmark

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process state shared between RecordingService (writer) and RecordActivity (reader).
 * Same process, so a plain singleton of StateFlows is enough — no need for broadcasts/AIDL.
 */
object RecordingState {
    val isRecording = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val elapsedMs = MutableStateFlow(0L)
    val inputPeak = MutableStateFlow(0f)
    val failureMessage = MutableStateFlow<String?>(null)
    /** Latest private encrypted recording. The persisted reference is restored after restart. */
    val lastRecording = MutableStateFlow<RecordingReference?>(null)

    fun reset() {
        failureMessage.value = null
        isPaused.value = false
        elapsedMs.value = 0L
        inputPeak.value = 0f
    }
}
