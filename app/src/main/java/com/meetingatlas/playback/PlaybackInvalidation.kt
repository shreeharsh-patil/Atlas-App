package com.meetingatlas.playback

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackInvalidation {
    private val revision = AtomicLong()
    private val changes = MutableStateFlow<Pair<String, Long>?>(null)
    val state = changes.asStateFlow()
    fun invalidate(meetingId: String) { changes.value = meetingId to revision.incrementAndGet() }
}
