package com.meetingatlas.playback

data class TimelinePosition(val segment: Int, val offsetMs: Long)

/** Continuous playback across containers. It never concatenates encoded M4A bytes. */
class PlaybackTimeline(val durations: List<Long>) {
    init {
        require(durations.all { it > 0 })
        // A corrupt container can report an absurd duration; a timeline longer than a year
        // is never legitimate and would overflow the arithmetic below.
        require(durations.sum() < MAX_TOTAL_MS) { "Timeline is implausibly long" }
    }
    val totalMs = durations.sum()
    val starts: List<Long> = durations.runningFold(0L, Long::plus).dropLast(1)
    fun locate(positionMs: Long): TimelinePosition {
        require(durations.isNotEmpty()) { "No playable segments" }
        val position = positionMs.coerceIn(0, totalMs)
        val index = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }.coerceIn(0, durations.lastIndex)
        return TimelinePosition(index, (position - starts[index]).coerceAtMost(durations[index]))
    }

    /**
     * The global position for a segment offset, or null when the segment is not part of
     * this timeline. Returning null instead of 0 makes a playlist/timeline desync visible
     * rather than silently jumping the scrubber to the start.
     */
    fun position(segment: Int, offsetMs: Long): Long? =
        if (segment in durations.indices) starts[segment] + offsetMs.coerceIn(0, durations[segment]) else null

    private companion object {
        const val MAX_TOTAL_MS = 365L * 24 * 3600 * 1000
    }
}
