package com.meetingatlas.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {
    @Test fun formatsHoursMinutesAndSecondsWithoutWrappingAtOneHour() {
        assertEquals("00:00:00", formatRecordingTime(0))
        assertEquals("00:59:59", formatRecordingTime(3_599_000))
        assertEquals("01:00:00", formatRecordingTime(3_600_000))
        assertEquals("12:34:56", formatRecordingTime(45_296_000))
    }
}
