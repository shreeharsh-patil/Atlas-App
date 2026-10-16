package com.meetingatlas.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide, atomic ownership. The capture service outlives Activity/ViewModel instances. */
object AudioWorkCoordinator {
    private val active = MutableStateFlow<String?>(null)
    val recording = active.asStateFlow()
    private var processing = false
    private var recovering = false
    @Synchronized fun startRecording(id: String): Boolean {
        if (processing || recovering || active.value != null) return false
        active.value = id
        return true
    }
    @Synchronized fun finishRecording(id: String) { if (active.value == id) active.value = null }
    @Synchronized fun startProcessing(): Boolean {
        if (processing || recovering || active.value != null) return false
        processing = true
        return true
    }
    @Synchronized fun finishProcessing() { processing = false }
    @Synchronized fun startRecovery(): Boolean {
        if (processing || recovering || active.value != null) return false
        recovering = true
        return true
    }
    @Synchronized fun finishRecovery() { recovering = false }
}
