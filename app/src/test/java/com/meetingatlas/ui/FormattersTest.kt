package com.meetingatlas.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test fun subMinuteRecordingsShowSecondsInsteadOfZeroMinutes() {
        assertEquals("1s", formatMeetingDuration(1))
        assertEquals("42s", formatMeetingDuration(42_000))
        assertEquals("59s", formatMeetingDuration(59_999))
    }

    @Test fun minutesAndHoursStayCompact() {
        assertEquals("1m", formatMeetingDuration(60_000))
        assertEquals("59m", formatMeetingDuration(59 * 60_000L))
        assertEquals("1h 00m", formatMeetingDuration(3_600_000))
        assertEquals("2h 05m", formatMeetingDuration(7_500_000))
    }

    @Test fun missingDurationIsLabelledRatherThanShownAsZero() {
        assertEquals("Duration unavailable", formatMeetingDuration(0))
        assertEquals("Duration unavailable", formatMeetingDuration(-1))
    }

    @Test fun metaLabelJoinsDateAndDuration() {
        assertEquals("16 Oct 2026  ·  12m", meetingMetaLabel("16 Oct 2026", 12 * 60_000L))
    }

    @Test fun playbackSpeedLabelHidesTrailingZero() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("1.25×", speedLabel(1.25f))
    }
}
