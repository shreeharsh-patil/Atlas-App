package com.meetingatlas.recording

/** All duration arithmetic uses monotonic time. Freeze before finalizing or doing disk work. */
class RecordingClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var runningSince: Long? = null
    val elapsedMs: Long get() = accumulated + (runningSince?.let { (now() - it).coerceAtLeast(0) } ?: 0)
    val startedAtElapsed: Long get() = runningSince ?: now()
    val elapsedBeforeStart: Long get() = accumulated
    fun start() { accumulated = 0; runningSince = now() }
    fun pause() { accumulated = elapsedMs; runningSince = null }
    fun resume() { if (runningSince == null) runningSince = now() }
    fun stop(): Long { pause(); return accumulated }
}

fun formatRecordingTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
