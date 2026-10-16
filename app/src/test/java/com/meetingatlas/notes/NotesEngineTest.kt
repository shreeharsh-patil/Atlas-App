package com.meetingatlas.notes

import com.meetingatlas.data.*
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

/** Known transcript excerpts and deliberately malformed provider outputs, never invented app content. */
class NotesEngineTest {
    private val sources = listOf(
        EvidenceSource("opening", 0, "Today we are planning the offline meeting recorder.", 0, 5000, "PROVIDER_SEGMENT"),
        EvidenceSource("proposal", 1, "Maya proposed cloud sync, but no decision was made.", 12000, 18000, "PROVIDER_SEGMENT"),
        EvidenceSource("middle", 2, "We agreed to use Room as the local database. The cache limit is 64 MB.", 7200000, 7212000, "PROVIDER_SEGMENT"),
        EvidenceSource("action", 3, "Ravi will profile battery usage by Friday.", 14400000, 14407000, "PROVIDER_SEGMENT"),
        EvidenceSource("study", 4, "A write-ahead log records changes before updating the main database.", 15000000, 15008000, "PROVIDER_SEGMENT"),
    )
    private fun entry(key: String, source: EvidenceSource, section: NoteSection = NoteSection.POINT, owner: String? = null, deadline: String? = null) = EntryPatch(key, "storage", section, source.text, 3, listOf(EvidenceRef(source.id, source.text)), owner, deadline, false, null)
    private fun patch() = WindowExtraction(listOf(TopicPatch("storage", "Local recording architecture")), listOf(
        entry("purpose", sources[0], NoteSection.PURPOSE), entry("cloud_proposal", sources[1], NoteSection.PROPOSAL), entry("room_decision", sources[2], NoteSection.DECISION), entry("battery_profile", sources[3], NoteSection.ACTION, "Ravi", "Friday"), entry("wal_definition", sources[4], NoteSection.DEFINITION),
    ), emptyList(), emptyList())
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid output was accepted") } catch (_: InvalidNotes) { } }

    @Test fun sourceLinkedFixturePreservesTechnicalMiddleProposalAndExplicitActionMetadata() {
        val result = patch()
        NotesValidator.validate(result, sources)
        assertEquals("middle", result.entries.first { it.key == "room_decision" }.sources.single().sourceId)
        assertEquals(NoteSection.PROPOSAL, result.entries.first { it.key == "cloud_proposal" }.section)
        assertEquals("Friday", result.entries.first { it.key == "battery_profile" }.deadline)
        assertEquals(result, NotesJson.decodeFromString<WindowExtraction>(NotesJson.encodeToString(result)))
    }
    @Test fun rejectsInventedSourceIdsAndChangedQuotes() {
        val base = patch()
        rejects { NotesValidator.validate(base.copy(entries = base.entries.mapIndexed { i, e -> if (i == 0) e.copy(sources = listOf(EvidenceRef("other-meeting", e.text))) else e }), sources) }
        rejects { NotesValidator.validate(base.copy(entries = base.entries.mapIndexed { i, e -> if (i == 0) e.copy(sources = listOf(EvidenceRef("opening", "We agreed to launch tomorrow."))) else e }), sources) }
    }
    @Test fun rejectsInferredOwnerOrDeadlineEvenWithValidTranscriptCitation() {
        val base = patch()
        rejects { NotesValidator.validate(base.copy(entries = base.entries.map { if (it.section == NoteSection.ACTION) it.copy(owner = "Maya") else it }), sources) }
        rejects { NotesValidator.validate(base.copy(entries = base.entries.map { if (it.section == NoteSection.ACTION) it.copy(deadline = "Monday") else it }), sources) }
    }
    @Test fun unaccountedTranscriptSegmentsCannotAdvanceTheCursor() {
        rejects { NotesValidator.validate(patch().copy(entries = patch().entries.filterNot { it.key == "room_decision" }), sources) }
        NotesValidator.validate(patch().copy(entries = patch().entries.filterNot { it.key == "room_decision" }, ignored = listOf(IgnoredSource("middle", "repetition"))), sources)
    }
    @Test fun topicRefreshCannotAlterUnrelatedTopics() {
        rejects { NotesValidator.validate(patch(), sources, setOf("storage"), topicScope = "battery") }
    }
    @Test fun changedDecisionMustReferenceAKnownDifferentEntry() {
        val base = patch()
        val revised = base.copy(entries = base.entries.map { if (it.key == "room_decision") it.copy(supersedesKey = "prior_decision") else it })
        rejects { NotesValidator.validate(revised, sources) }
        NotesValidator.validate(revised, sources, knownEntries = setOf("prior_decision"))
    }
    @Test fun noChapterCanBeInventedFromApproximateOrUnknownTimes() {
        val withChapter = patch().copy(chapters = listOf(ChapterPatch("architecture", "storage", "Architecture", listOf("opening", "proposal"))))
        NotesValidator.validate(withChapter, sources)
        rejects { NotesValidator.validate(withChapter, sources.map { it.copy(precision = "CHUNK") }) }
        rejects { NotesValidator.validate(withChapter, sources.map { it.copy(startMs = null, endMs = null, precision = "UNKNOWN") }) }
    }
    @Test fun chaptersCannotSkipInterveningSourceSegments() {
        rejects { NotesValidator.validate(patch().copy(chapters = listOf(ChapterPatch("bad", "storage", "Noncontinuous", listOf("opening", "middle")))), sources) }
    }
    @Test(expected = SerializationException::class) fun unknownProviderFieldsAreNotSilentlyAccepted() {
        NotesJson.decodeFromString<WindowExtraction>("""{"topics":[],"entries":[],"ignored":[],"chapters":[],"made_up_fact":"launch"}""")
    }
    @Test fun longMeetingWindowingPreservesEveryOriginalSourceIdAndText() {
        val transcript = List(600) { i -> EvidenceSource("segment-$i", i, "Technical topic $i: " + "detail ".repeat(400), i * 72000L, (i + 1) * 72000L, "PROVIDER_SEGMENT") }
        val processed = mutableListOf<EvidenceSource>()
        while (processed.size < transcript.size) {
            val window = TranscriptWindows.next(transcript.drop(processed.size))
            assertTrue(window.sumOf { it.text.length } <= TranscriptWindows.MAX_WINDOW_CHARS)
            assertTrue(window.isNotEmpty()); processed += window
        }
        assertEquals(transcript, processed)
        assertTrue(processed.any { it.id == "segment-300" })
    }
    @Test fun stableIdentifiersDoNotCollideAcrossMeetingsOrRecordTypes() {
        assertEquals(stableNoteId("a", "entry", "cache"), stableNoteId("a", "entry", "cache"))
        assertNotEquals(stableNoteId("a", "entry", "cache"), stableNoteId("b", "entry", "cache"))
        assertNotEquals(stableNoteId("a", "entry", "cache"), stableNoteId("a", "topic", "cache"))
    }
}
