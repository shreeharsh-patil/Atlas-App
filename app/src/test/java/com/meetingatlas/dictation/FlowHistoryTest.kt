package com.meetingatlas.dictation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowHistoryTest {
    @Test fun historyUsesLocalCalendarDayAcrossUtcMidnight() {
        val timestamp = Instant.parse("2026-11-14T21:00:00Z").toEpochMilli()
        val day = historyDay(timestamp, ZoneId.of("Asia/Kolkata"))
        assertEquals(LocalDate.of(2026, 11, 15), day)
        assertEquals("Today", historyHeading(day, day, Locale.US))
        assertEquals("Yesterday", historyHeading(day, day.plusDays(1), Locale.US))
    }

    @Test fun datesFromEarlierYearsIncludeTheirYear() {
        assertEquals("Dec 31, 2025", historyHeading(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 3), Locale.US))
    }
}
