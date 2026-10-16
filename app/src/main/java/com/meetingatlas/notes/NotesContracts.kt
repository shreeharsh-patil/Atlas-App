package com.meetingatlas.notes

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class SummaryView { QUICK, STANDARD, DETAILED, STUDY }
enum class MeetingTemplate(val label: String, val focus: String) {
    GENERAL("General meeting", "purpose, discussion, decisions and next steps"),
    LECTURE("College lecture", "concepts, definitions, worked examples, formulas and revision points actually discussed"),
    TECHNICAL("Technical discussion", "requirements, constraints, architecture, alternatives and technical reasoning"),
    STANDUP("Team standup", "progress, blockers and explicitly committed next steps"),
    INTERVIEW("Interview", "questions, responses and established experience without invented evaluation"),
    PLANNING("Project planning", "scope, dependencies, risks, agreed responsibilities and dates"),
    BRAINSTORM("Brainstorming", "ideas and alternatives, clearly separated from agreed decisions"),
}
@Serializable enum class NoteSection {
    PURPOSE, POINT, EXPLANATION, EXAMPLE, ALTERNATIVE, CONCERN, SOLUTION, DECISION, PROPOSAL,
    ACTION, PROPOSED_ACTION, QUESTION, FOLLOW_UP, CONCLUSION, DEFINITION, FORMULA, PROCEDURE, CONCEPT,
}
@Serializable data class EvidenceRef(val sourceId: String, val quote: String)
@Serializable data class TopicPatch(val key: String, val title: String)
@Serializable data class EntryPatch(
    val key: String, val topicKey: String, val section: NoteSection, val text: String,
    val importance: Int, val sources: List<EvidenceRef>, val owner: String?, val deadline: String?,
    val uncertain: Boolean, val supersedesKey: String?,
)
@Serializable data class IgnoredSource(val sourceId: String, val reason: String)
@Serializable data class ChapterPatch(val key: String, val topicKey: String, val title: String, val sourceIds: List<String>)
@Serializable data class WindowExtraction(
    val topics: List<TopicPatch>, val entries: List<EntryPatch>, val ignored: List<IgnoredSource>, val chapters: List<ChapterPatch>,
)
@Serializable data class EvidenceSource(
    val id: String, val order: Int, val text: String, val startMs: Long?, val endMs: Long?, val precision: String, val revision: Int = 1,
)
@Serializable data class PriorEntry(val key: String, val topicKey: String, val section: NoteSection, val text: String, val userEdited: Boolean)
@Serializable data class ExtractionInput(val template: String, val focus: String, val sources: List<EvidenceSource>, val knownTopics: List<TopicPatch>, val priorEntries: List<PriorEntry>)

val NotesJson = Json { ignoreUnknownKeys = false; isLenient = false; encodeDefaults = true }
fun stableNoteId(meetingId: String, type: String, key: String): String = UUID.nameUUIDFromBytes("$meetingId|$type|$key".toByteArray(Charsets.UTF_8)).toString()

class InvalidNotes(message: String) : IllegalArgumentException(message)

/** Citation and coverage checks establish source linkage, not semantic truth or entailment. */
object NotesValidator {
    private val keyPattern = Regex("[a-z0-9][a-z0-9._-]{0,95}")
    private val excludedReasons = setOf("social_chatter", "repetition", "unclear", "context_only")
    fun validate(patch: WindowExtraction, sources: List<EvidenceSource>, knownTopics: Set<String> = emptySet(), knownEntries: Set<String> = emptySet(), topicScope: String? = null) {
        fun verify(condition: Boolean, message: String) { if (!condition) throw InvalidNotes(message) }
        val sourceMap = sources.associateBy { it.id }
        verify(sources.isNotEmpty() && sourceMap.size == sources.size, "Invalid source window")
        verify(sources.zipWithNext().all { it.first.order < it.second.order }, "Transcript sources are not in order")
        verify(patch.topics.size <= 32 && patch.entries.size <= 64 && patch.chapters.size <= 12, "The model returned too much output for one window")
        verify(patch.topics.map { it.key }.distinct().size == patch.topics.size && patch.entries.map { it.key }.distinct().size == patch.entries.size, "Duplicate topic or entry keys")
        val topics = knownTopics + patch.topics.map { it.key }
        patch.topics.forEach { verify(keyPattern.matches(it.key) && it.title.isNotBlank() && it.title.length <= 160, "Invalid topic"); verify(topicScope == null || it.key == topicScope, "A topic refresh changed an unrelated topic") }
        patch.entries.forEach { entry ->
            verify(keyPattern.matches(entry.key) && entry.topicKey in topics && (topicScope == null || entry.topicKey == topicScope), "Invalid entry identity or topic")
            verify(entry.text.isNotBlank() && entry.text.length <= 1800 && entry.importance in 1..3, "Invalid note text or importance")
            verify(entry.sources.isNotEmpty() && entry.sources.size <= 12 && entry.sources.map { it.sourceId }.distinct().size == entry.sources.size, "A factual item has missing or duplicate sources")
            entry.sources.forEach { ref ->
                val source = sourceMap[ref.sourceId]
                verify(source != null && ref.quote.isNotBlank() && ref.quote.trim().length >= minOf(12, source.text.trim().length) && ref.quote.length <= 2000 && source.text.contains(ref.quote), "A citation is missing or its quote is not in the transcript")
            }
            val quotes = entry.sources.joinToString("\n") { it.quote }
            verify(entry.owner == null || (entry.section == NoteSection.ACTION && entry.owner.isNotBlank() && quotes.contains(entry.owner)), "An action owner lacks quoted evidence")
            verify(entry.deadline == null || (entry.section == NoteSection.ACTION && entry.deadline.isNotBlank() && quotes.contains(entry.deadline)), "An action deadline lacks quoted evidence")
            verify(entry.supersedesKey == null || (entry.supersedesKey in knownEntries && entry.supersedesKey != entry.key), "A revised decision refers to an unknown or identical entry")
        }
        verify(patch.ignored.map { it.sourceId }.distinct().size == patch.ignored.size, "Duplicate excluded source")
        patch.ignored.forEach { verify(it.sourceId in sourceMap && it.reason in excludedReasons, "Invalid exclusion reason") }
        val covered = patch.entries.flatMap { it.sources }.map { it.sourceId }.toSet() + patch.ignored.map { it.sourceId }
        verify(sourceMap.keys.all { it in covered }, "The model did not account for every transcript segment")
        val chapterSources = mutableSetOf<String>()
        patch.chapters.forEach { chapter ->
            verify(topicScope == null, "A topic refresh cannot infer new chapter boundaries")
            verify(keyPattern.matches(chapter.key) && chapter.topicKey in topics && (topicScope == null || chapter.topicKey == topicScope) && chapter.title.isNotBlank() && chapter.title.length <= 160, "Invalid chapter")
            verify(chapter.sourceIds.size >= 2 && chapter.sourceIds.distinct().size == chapter.sourceIds.size, "A chapter needs distinct supporting source segments")
            verify(chapter.sourceIds.none { it in chapterSources }, "Proposed chapters overlap")
            chapterSources += chapter.sourceIds
            val members = chapter.sourceIds.map { sourceMap[it] ?: throw InvalidNotes("Unknown chapter source") }
            verify(members.all { it.startMs != null && it.endMs != null && it.precision == "PROVIDER_SEGMENT" }, "Chapter timing is not provided by the transcript")
            val indices = members.map { sources.indexOf(it) }.sorted()
            verify(indices.last() - indices.first() + 1 == indices.size, "Chapter sources are not a continuous transcript range")
            verify(patch.entries.any { it.topicKey == chapter.topicKey && it.sources.any { ref -> ref.sourceId in chapter.sourceIds } }, "Chapter has no related notes")
        }
    }
}

object TranscriptWindows {
    const val MAX_SOURCE_CHARS = 6000
    const val MAX_WINDOW_CHARS = 10000
    fun next(sources: List<EvidenceSource>): List<EvidenceSource> {
        val result = mutableListOf<EvidenceSource>()
        var chars = 0
        for (source in sources) {
            require(source.text.length <= MAX_SOURCE_CHARS) { "Transcript segment exceeds its storage budget" }
            if (result.isNotEmpty() && chars + source.text.length > MAX_WINDOW_CHARS) break
            result += source; chars += source.text.length
        }
        return result
    }
}
