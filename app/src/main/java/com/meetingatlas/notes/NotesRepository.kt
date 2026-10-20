package com.meetingatlas.notes

import androidx.room.withTransaction
import com.meetingatlas.data.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

fun TranscriptSegmentEntity.asSource() = EvidenceSource(id, orderIndex, text, startMs, endMs, precision, revision)
private fun canonicalText(text: String) = text.lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()

@Singleton
class NotesRepository @Inject constructor(private val db: MeetingDatabase) {
    private val dao get() = db.notesDao()
    fun document(id: String) = dao.observeDocument(id)
    fun jobs(id: String) = dao.jobs(id)
    fun sources(id: String, limit: Int) = dao.observeSources(id, limit)
    fun searchSources(id: String, query: String, limit: Int) = dao.searchSources(id, query, limit)
    suspend fun chapterTranscript(id: String) = dao.chapterTranscript(id)
    suspend fun chapterNotes(id: String) = dao.chapterNotes(id)
    fun history(id: String) = dao.history(id)
    fun snapshots(id: String) = dao.snapshots(id)
    suspend fun evidence(id: String) = dao.evidenceForEntry(id)
    suspend fun collapse(id: String, collapsed: Boolean) = dao.collapseTopic(id, collapsed)
    suspend fun renameTopic(id: String, title: String) { require(title.isNotBlank() && title.length <= 160); dao.renameTopic(id, title.trim()) }
    suspend fun toggleHighlight(id: String) = db.withTransaction { dao.entry(id)?.let { dao.saveEntry(it.copy(highlighted = !it.highlighted)) } }
    suspend fun addToMap(id: String) = db.withTransaction { dao.entry(id)?.let { dao.saveEntry(it.copy(inMap = true)) } }
    suspend fun setCompleted(id: String, completed: Boolean) = db.withTransaction { dao.entry(id)?.let { require(it.section == "ACTION"); dao.saveEntry(it.copy(completed = completed)) } }

    suspend fun apply(job: NotesJobEntity, sources: List<EvidenceSource>, patch: WindowExtraction, cursor: Int) = db.withTransaction {
        val durableJob = requireNotNull(dao.job(job.id)) { "Processing job no longer exists" }
        if (durableJob.status == "CANCELLED") throw kotlinx.coroutines.CancellationException("Processing cancelled")
        check(durableJob.meetingId == job.meetingId && durableJob.topicId == job.topicId) { "Processing scope changed" }
        if (cursor <= durableJob.cursor) return@withTransaction
        val state = requireNotNull(dao.state(job.meetingId))
        val snapshot = state.revision + 1
        val topics = dao.topics(job.meetingId).associateBy { it.topicKey }.toMutableMap()
        val existing = dao.entries(job.meetingId).toMutableList()
        // A model key that was folded into an existing entry is remembered so a later
        // supersedesKey referring to the new key still resolves.
        val keyAliases = mutableMapOf<String, String>()
        sources.forEach { source ->
            val stored = requireNotNull(dao.source(source.id))
            check(stored.meetingId == job.meetingId && stored.revision == source.revision && stored.text == source.text) { "Transcript evidence changed while notes were generated. Retry with the current transcript." }
        }
        NotesValidator.validate(patch, sources, topics.keys, existing.map { it.entryKey }.toSet(), job.topicId?.let { dao.topic(it)?.topicKey })
        val scopedTopicKey = job.topicId?.let { dao.topic(it)?.topicKey }
        for (topic in patch.topics) {
            val known = topics[topic.key]
            when {
                known == null -> {
                    val row = NoteTopicEntity(stableNoteId(job.meetingId, "topic", topic.key), job.meetingId, topic.key, topic.title, topics.size)
                    dao.saveTopics(listOf(row)); topics[topic.key] = row
                }
                // A refresh may improve the title of the topic it is scoped to.
                job.topicId == null || topic.key == scopedTopicKey -> {
                    val updated = known.copy(title = topic.title)
                    dao.saveTopics(listOf(updated)); topics[topic.key] = updated
                }
                // Topics outside the refresh scope are ignored, not fatal.
                else -> Unit
            }
        }
        for (change in patch.entries) {
            val topic = requireNotNull(topics[change.topicKey])
            val previous = existing.firstOrNull { it.entryKey == change.key } ?: existing.firstOrNull { it.topicId == topic.id && it.section == change.section.name && canonicalText(it.text) == canonicalText(change.text) }
            require(previous == null || (previous.topicId == topic.id && previous.section == change.section.name)) { "A stable note key was reused for a different section or topic" }
            val id = previous?.id ?: stableNoteId(job.meetingId, "entry", change.key)
            if (previous != null && previous.entryKey != change.key) keyAliases[change.key] = previous.entryKey
            val oldSources = previous?.let { dao.evidenceForEntry(it.id) }.orEmpty()
            val owner = change.owner ?: previous?.owner
            val deadline = change.deadline ?: previous?.deadline
            val same = previous != null && previous.text == change.text && previous.owner == owner && previous.deadline == deadline
            val refsUnchanged = change.sources.all { ref -> oldSources.any { it.sourceId == ref.sourceId && it.quote == ref.quote } }
            if (same && refsUnchanged) continue
            val number = dao.nextEntryRevision(id)
            val isCandidate = previous?.userEdited == true && !same
            val row = NoteEntryEntity(id, job.meetingId, previous?.entryKey ?: change.key, topic.id, change.section.name, change.text, max(previous?.importance ?: 0, change.importance), owner, deadline,
                if (change.uncertain) "UNCERTAIN" else "SOURCE_LINKED", false, previous?.highlighted ?: false, previous?.completed ?: false, previous?.inMap ?: (change.importance >= 2), number, previous?.supersededBy, System.currentTimeMillis(), previous?.displayOrder ?: existing.size)
            if (previous == null || !isCandidate) {
                // A user-edited note keeps its marker, but an uncertain model claim must
                // stay visibly uncertain.
                val appliedRow = if (previous?.userEdited == true) {
                    row.copy(userEdited = true, verification = if (row.verification == "UNCERTAIN") "UNCERTAIN" else "USER_EDITED")
                } else {
                    row
                }
                dao.saveEntry(appliedRow)
                existing.removeAll { it.id == id }; existing += appliedRow
            }
            val revision = NoteRevisionEntity("$id:$number", id, number, change.text, owner, deadline, if (isCandidate) "AI_CANDIDATE" else "AI", !isCandidate, snapshot, System.currentTimeMillis())
            dao.revision(revision)
            val retained = if (previous != null) oldSources.map { NoteEvidenceEntity(revision.id, it.sourceId, it.sourceRevision, it.quote) } else emptyList()
            val incoming = change.sources.map { ref -> NoteEvidenceEntity(revision.id, ref.sourceId, requireNotNull(sources.firstOrNull { it.id == ref.sourceId }).revision, ref.quote) }
            dao.evidence((retained + incoming).associateBy { it.transcriptId }.values.toList())
            change.supersedesKey?.let { key ->
                val resolved = keyAliases[key] ?: key
                val old = existing.firstOrNull { it.entryKey == resolved } ?: error("Revised entry was not found")
                require(old.id != id && old.section == row.section && row.section in setOf("DECISION", "ACTION") && old.supersededBy == null && row.supersededBy == null) { "Invalid revised decision or action" }
                if (!isCandidate && !old.userEdited) {
                    dao.supersede(old.id, id)
                    existing.removeAll { it.id == old.id }; existing += old.copy(supersededBy = id)
                }
            }
        }
        for (chapter in patch.chapters) {
            val related = sources.filter { it.id in chapter.sourceIds }
            val id = stableNoteId(job.meetingId, "chapter", "${chapter.key}:${related.minBy { it.order }.id}")
            val previousTopic = requireNotNull(topics[chapter.topicKey])
            dao.saveChapters(listOf(MeetingChapterEntity(id, job.meetingId, previousTopic.id, chapter.title, related.minOf { requireNotNull(it.startMs) }, related.maxOf { requireNotNull(it.endMs) })))
            dao.chapterSources(chapter.sourceIds.map { ChapterSourceEntity(id, it) })
        }
        dao.saveJob(job.copy(cursor = cursor, status = "RUNNING", error = null, updatedAt = System.currentTimeMillis()))
        if (job.topicId == null) {
            val cited = patch.entries.flatMap { it.sources }.map { it.sourceId }.toSet()
            val ignored = patch.ignored.associate { it.sourceId to it.reason }
            dao.saveCoverage(sources.map { SourceCoverageEntity(it.id, if (it.id in cited) "CITED" else "EXCLUDED:${requireNotNull(ignored[it.id])}", snapshot) })
        }
        dao.saveState(state.copy(status = if (job.topicId == null) "RECONCILING" else state.status, revision = snapshot, summarizedOrder = if (job.topicId == null) cursor else state.summarizedOrder, updatedAt = System.currentTimeMillis()))
        dao.snapshot(SummarySnapshotEntity("${job.meetingId}:$snapshot", job.meetingId, snapshot, cursor, if (job.topicId == null) "DRAFT" else "TOPIC_REFRESH", System.currentTimeMillis()))
    }

    suspend fun edit(id: String, expectedRevision: Int, text: String) = db.withTransaction {
        require(text.isNotBlank() && text.length <= 10000) { "A note must contain 1–10000 characters" }
        val entry = requireNotNull(dao.entry(id))
        check(entry.revision == expectedRevision) { "This note changed while you edited it. Reopen it and try again." }
        val state = requireNotNull(dao.state(entry.meetingId))
        val number = dao.nextEntryRevision(id)
        val revisionId = "$id:$number"
        val evidence = dao.evidenceForEntry(id)
        dao.revision(NoteRevisionEntity(revisionId, id, number, text.trim(), entry.owner, entry.deadline, "USER", true, state.revision + 1, System.currentTimeMillis()))
        dao.evidence(evidence.map { NoteEvidenceEntity(revisionId, it.sourceId, it.sourceRevision, it.quote) })
        dao.saveEntry(entry.copy(text = text.trim(), revision = number, userEdited = true, verification = "USER_EDITED", updatedAt = System.currentTimeMillis()))
        dao.saveState(state.copy(revision = state.revision + 1, updatedAt = System.currentTimeMillis()))
        dao.snapshot(SummarySnapshotEntity("${entry.meetingId}:${state.revision + 1}", entry.meetingId, state.revision + 1, state.summarizedOrder, "USER_EDIT", System.currentTimeMillis()))
    }

    suspend fun export(title: String, document: NotesDocument, view: SummaryView): String = buildString {
        append(SummaryProjection.markdown(title, document, view))
        append("## Evidence\n\nSource links verify where a quote came from; they do not independently prove a generated claim.\n\n")
        for (entry in SummaryProjection.select(document.entries, view)) {
            append("### ").append(entry.entryKey).append("\n\n").append(entry.text).append("\n\n")
            for (source in dao.evidenceForEntry(entry.id)) {
                append("- ").append(source.sourceId)
                source.startMs?.let { append(" · ").append(com.meetingatlas.recording.formatRecordingTime(it)).append(if (source.precision == "CHUNK") " (approximate chunk interval)" else "") }
                append(": “").append(source.quote).append("”\n")
            }
            append("\n")
        }
    }
}

object SummaryProjection {
    private val standard = setOf("PURPOSE", "POINT", "EXPLANATION", "DECISION", "PROPOSAL", "ACTION", "PROPOSED_ACTION", "QUESTION", "FOLLOW_UP", "CONCLUSION")
    private val study = setOf("DEFINITION", "CONCEPT", "FORMULA", "PROCEDURE", "EXAMPLE", "EXPLANATION", "QUESTION")
    fun select(entries: List<NoteEntryEntity>, view: SummaryView): List<NoteEntryEntity> {
        val current = entries.filter { it.supersededBy == null }.sortedWith(compareByDescending<NoteEntryEntity> { it.importance }.thenBy { it.displayOrder }.thenBy { it.id })
        return when (view) {
            SummaryView.QUICK -> {
                val key = current.filter { it.section in setOf("PURPOSE", "DECISION", "ACTION", "CONCLUSION") }
                val topicRepresentatives = current.distinctBy { it.topicId }
                (key.take(5) + topicRepresentatives + current).distinctBy { it.id }.take(8)
            }
            SummaryView.STANDARD -> current.filter { it.section in standard && (it.importance >= 2 || it.section in setOf("ACTION", "DECISION", "QUESTION")) }.sortedBy { it.displayOrder }
            SummaryView.DETAILED -> entries.sortedWith(compareBy<NoteEntryEntity> { it.displayOrder }.thenBy { it.id })
            SummaryView.STUDY -> current.filter { it.section in study }.sortedBy { it.displayOrder }
        }
    }
    fun markdown(title: String, document: NotesDocument, view: SummaryView): String = buildString {
        append("# ").append(title).append("\n\n").append(view.name.lowercase().replaceFirstChar { it.uppercase() }).append(" notes · ").append(document.state.status.lowercase()).append("\n\n")
        val entries = select(document.entries, view)
        for (topic in document.topics.sortedBy { it.position }) {
            val notes = entries.filter { it.topicId == topic.id }
            if (notes.isEmpty()) continue
            append("## ").append(topic.title).append("\n\n")
            for (entry in notes) {
                append("- **").append(entry.section.lowercase().replace('_', ' ')).append(":** ").append(entry.text)
                entry.owner?.let { append(" · Owner: ").append(it) }; entry.deadline?.let { append(" · Due: ").append(it) }
                if (entry.userEdited) append(" [user edited]")
                if (entry.supersededBy != null) append(" [revised later]")
                append("\n")
            }
            append("\n")
        }
    }
}
