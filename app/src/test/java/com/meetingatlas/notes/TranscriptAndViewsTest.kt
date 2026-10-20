package com.meetingatlas.notes

import com.meetingatlas.data.NoteEntryEntity
import org.junit.Assert.*
import org.junit.Test

class TranscriptAndViewsTest {
    @Test fun providerTimestampsAreNormalizedAgainstTheActualChunkStartAndRetryIdsStayStable() {
        val raw = """{"text":"Use Room.","segments":[{"text":"Use Room.","start":0.4,"end":2.1}],"usage":{"seconds":3}}"""
        val first = TranscriptNormalizer.normalize(raw, "meeting", 2, 240000, 3000)
        assertEquals(240400L, first.single().startMs)
        assertEquals(242100L, first.single().endMs)
        assertEquals("PROVIDER_SEGMENT", first.single().precision)
        assertEquals(first, TranscriptNormalizer.normalize(raw, "meeting", 2, 240000, 3000))
    }
    @Test fun absentTimestampsUseExplicitlyApproximateChunkIntervals() {
        val result = TranscriptNormalizer.normalize("""{"text":"A timestamp-free response."}""", "m", 0, 8000, 120000).single()
        assertEquals("CHUNK", result.precision)
        assertEquals(8000L, result.startMs)
        assertEquals(128000L, result.endMs)
    }
    @Test(expected = IllegalArgumentException::class) fun outOfRangeProviderTimestampsAreRejected() {
        TranscriptNormalizer.normalize("""{"segments":[{"text":"Wrong interval.","start":0.0,"end":200.0}]}""", "m", 0, 0, 120000)
    }
    @Test fun aSmallProviderTimestampOverrunIsClampedInsteadOfFailingTheWholeChunk() {
        // The AAC encoder delay makes the provider's last segment end slightly after the
        // container duration. That must not abort the meeting.
        val result = TranscriptNormalizer.normalize(
            """{"segments":[{"text":"Nearly at the end.","start":0.0,"end":120.9}]}""", "m", 0, 0, 120000,
        ).single()
        assertEquals(0L, result.startMs)
        assertEquals(120000L, result.endMs)
    }
    @Test fun nullSegmentsAndNullTextAreTolerated() {
        val result = TranscriptNormalizer.normalize("""{"text":null,"segments":null}""", "m", 0, 0, 10000)
        assertTrue(result.isEmpty())
    }
    @Test fun aPlainTranscriptWithoutSegmentsIsStillUsable() {
        val result = TranscriptNormalizer.normalize("""{"text":"A plain transcript."}""", "m", 0, 0, 10000).single()
        assertEquals("A plain transcript.", result.text)
        assertEquals("CHUNK", result.precision)
    }
    @Test fun noSpeechDoesNotProduceFakeContent() { assertTrue(TranscriptNormalizer.normalize("""{"text":""}""", "m", 0, 0, 10000).isEmpty()) }
    private fun item(id: String, section: String, importance: Int = 3, superseded: String? = null) = NoteEntryEntity(id, "m", id, "topic-${id.toInt() % 3}", section, "Recorded detail $id", importance, null, null, "SOURCE_LINKED", false, false, false, true, 1, superseded, id.toLong(), id.toInt())
    @Test fun allFourViewsUseTheSameSavedKnowledgeAndDetailedKeepsTheMiddle() {
        val entries = (0..19).map { item(it.toString(), when (it) { 0 -> "PURPOSE"; 5 -> "DECISION"; 10 -> "FORMULA"; 15 -> "ACTION"; 18 -> "PROPOSAL"; else -> "EXPLANATION" }, if (it % 2 == 0) 1 else 3) }
        val quick = SummaryProjection.select(entries, SummaryView.QUICK)
        assertTrue(quick.size <= 8)
        val detailed = SummaryProjection.select(entries, SummaryView.DETAILED)
        assertEquals(entries.map { it.id }, detailed.map { it.id })
        assertTrue(detailed.any { it.id == "10" })
        assertTrue(detailed.size > SummaryProjection.select(entries, SummaryView.STANDARD).size)
        assertTrue(SummaryProjection.select(entries, SummaryView.STUDY).any { it.section == "FORMULA" })
        SummaryView.entries.forEach { view -> assertTrue(SummaryProjection.select(entries, view).all { it in entries }) }
    }
    @Test fun revisedDecisionsStayInDetailedHistoryButLeaveCurrentQuickView() {
        val entries = listOf(item("0", "DECISION", superseded = "1"), item("1", "DECISION"))
        assertEquals(listOf("1"), SummaryProjection.select(entries, SummaryView.QUICK).map { it.id })
        assertEquals(2, SummaryProjection.select(entries, SummaryView.DETAILED).size)
    }
    @Test fun aShortMeetingIsNotPaddedWithInventedBullets() {
        assertEquals(1, SummaryProjection.select(listOf(item("0", "POINT")), SummaryView.QUICK).size)
        assertTrue(SummaryProjection.select(emptyList(), SummaryView.STUDY).isEmpty())
    }
}
