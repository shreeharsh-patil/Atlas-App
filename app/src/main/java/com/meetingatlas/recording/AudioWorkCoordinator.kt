package com.meetingatlas.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Process-wide, atomic ownership. The capture service outlives Activity/ViewModel instances.
 *
 * Processing and recovery are owner-guarded: a caller that did not acquire the lock cannot
 * release it, so two overlapping workers cannot clear each other's claim and let a recording
 * start while the AI is still reading the audio.
 */
object AudioWorkCoordinator {
    private val active = MutableStateFlow<String?>(null)
    val recording = active.asStateFlow()
    private var processingOwner: String? = null
    private var recoveringOwner: String? = null

    @Synchronized fun startRecording(id: String): Boolean {
        if (processingOwner != null || recoveringOwner != null || active.value != null) return false
        active.value = id
        return true
    }

    @Synchronized fun finishRecording(id: String) { if (active.value == id) active.value = null }

    /** Acquires the processing lock. The returned owner must be passed to [finishProcessing]. */
    @Synchronized fun startProcessing(): String? {
        if (processingOwner != null || recoveringOwner != null || active.value != null) return null
        processingOwner = UUID.randomUUID().toString()
        return processingOwner
    }

    @Synchronized fun finishProcessing(owner: String?) {
        if (owner != null && processingOwner == owner) processingOwner = null
    }

    /** Acquires the recovery lock. The returned owner must be passed to [finishRecovery]. */
    @Synchronized fun startRecovery(): String? {
        if (processingOwner != null || recoveringOwner != null || active.value != null) return null
        recoveringOwner = UUID.randomUUID().toString()
        return recoveringOwner
    }

    @Synchronized fun finishRecovery(owner: String?) {
        if (owner != null && recoveringOwner == owner) recoveringOwner = null
    }
}
