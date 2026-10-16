package com.meetingatlas.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {
    @Test fun pauseResumeUsesMonotonicTimeAndDoesNotCountPausesOrFinalization() {
        var now = 1000L
        val clock = RecordingClock { now }
        clock.start()
        now += 5000
        clock.pause()
        clock.pause()
        now += 60000
        assertEquals(5000, clock.elapsedMs)
        clock.resume()
        clock.resume()
        now += 3000
        assertEquals(8000, clock.stop())
        now += 10000
        assertEquals(8000, clock.elapsedMs)
    }

    @Test fun newSessionResetsPreviousElapsedTime() {
        var now = 0L
        val clock = RecordingClock { now }
        clock.start(); now += 3600000; clock.stop()
        clock.start(); now += 500
        assertEquals(500, clock.elapsedMs)
    }
    @Test fun formatsHoursMinutesAndSecondsWithoutWrappingAtOneHour() {
        assertEquals("00:00:00", formatRecordingTime(0))
        assertEquals("00:59:59", formatRecordingTime(3_599_000))
        assertEquals("01:00:00", formatRecordingTime(3_600_000))
        assertEquals("12:34:56", formatRecordingTime(45_296_000))
    }
}
