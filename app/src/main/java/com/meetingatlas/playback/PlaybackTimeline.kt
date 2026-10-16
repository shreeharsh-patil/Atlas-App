package com.meetingatlas.playback

data class TimelinePosition(val segment: Int, val offsetMs: Long)

/** Continuous playback across containers. It never concatenates encoded M4A bytes. */
class PlaybackTimeline(val durations: List<Long>) {
    init { require(durations.all { it > 0 }) }
    val totalMs = durations.sum()
    val starts: List<Long> = durations.runningFold(0L, Long::plus).dropLast(1)
    fun locate(positionMs: Long): TimelinePosition {
        require(durations.isNotEmpty()) { "No playable segments" }
        val position = positionMs.coerceIn(0, totalMs)
        val index = starts.binarySearch(position).let { if (it >= 0) it else -it - 2 }.coerceIn(0, durations.lastIndex)
        return TimelinePosition(index, (position - starts[index]).coerceAtMost(durations[index]))
    }
    fun position(segment: Int, offsetMs: Long): Long = if (segment in durations.indices) starts[segment] + offsetMs.coerceIn(0, durations[segment]) else 0
}
