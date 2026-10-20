package com.meetingatlas.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    private val timeline = PlaybackTimeline(listOf(1200000, 1200123, 37000))
    @Test fun seekingAtBoundarySelectsNextSegmentWithoutGap() {
        assertEquals(TimelinePosition(1, 0), timeline.locate(1200000))
        assertEquals(TimelinePosition(1, 7123), timeline.locate(1207123))
        assertEquals(TimelinePosition(2, 37000), timeline.locate(Long.MAX_VALUE))
        assertEquals(TimelinePosition(0, 0), timeline.locate(-100))
    }
    @Test fun positionRoundTripsAcrossManyHours() {
        val longMeeting = PlaybackTimeline(List(40) { 1200000L })
        val target = longMeeting.locate(12 * 3600000L + 500)
        assertEquals(12 * 3600000L + 500, longMeeting.position(target.segment, target.offsetMs))
    }
    @Test fun anOutOfRangeSegmentReportsNoPositionInsteadOfSilentlyZeroing() {
        // A playlist that no longer matches the timeline must be visible, not silently
        // reported as position zero.
        assertEquals(null, timeline.position(9, 1000))
        assertEquals(null, timeline.position(-1, 0))
        assertEquals(1200000L, timeline.position(1, 0))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyTimelineCannotSeek() { PlaybackTimeline(emptyList()).locate(0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroDurationSegments() { PlaybackTimeline(listOf(0)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnImplausiblyLongTimeline() {
        PlaybackTimeline(listOf(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2))
    }
}
