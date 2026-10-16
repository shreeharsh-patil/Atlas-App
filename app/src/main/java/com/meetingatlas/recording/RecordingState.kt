package com.meetingatlas.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Preparing(val meetingId: String) : RecordingState
    data class Recording(val meetingId: String, val startedAtElapsed: Long, val elapsedBeforeStart: Long = 0) : RecordingState
    data class Paused(val meetingId: String, val elapsedMs: Long) : RecordingState
    data class Finalizing(val meetingId: String) : RecordingState
    data class Saved(val meetingId: String) : RecordingState
    data class Interrupted(val meetingId: String, val reason: String) : RecordingState
    data class Failed(val meetingId: String?, val message: String) : RecordingState
}

object RecordingProcessState {
    private val mutable = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = mutable
    internal fun update(value: RecordingState) { mutable.value = value }
}
