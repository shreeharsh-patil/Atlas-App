package com.meetingatlas.recording

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingHealth(val input: String = "System microphone", val storageMinutes: Long? = null, val warning: String? = null, val bookmarkMessage: String? = null)
object RecorderTelemetry {
    internal val samples = MutableSharedFlow<Float>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val amplitude = samples.asSharedFlow()
    internal val healthMutable = MutableStateFlow(RecordingHealth())
    val health = healthMutable.asStateFlow()
}
