package com.meetingatlas.playback

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Signals that a meeting's audio files have been removed from disk.
 *
 * A `StateFlow` would conflate: if two meetings were invalidated before the player
 * collected, only the last one would be seen and the first would keep playing a file
 * that had just been deleted. A `SharedFlow` delivers every invalidation.
 */
object PlaybackInvalidation {
    private val revision = AtomicLong()
    private val events = MutableSharedFlow<Pair<String, Long>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val invalidations = events.asSharedFlow()

    fun invalidate(meetingId: String) { events.tryEmit(meetingId to revision.incrementAndGet()) }

    /** The revision of the most recent invalidation. */
    fun currentRevision(): Long = revision.get()
}
