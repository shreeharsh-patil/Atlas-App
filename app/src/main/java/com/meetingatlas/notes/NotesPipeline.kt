package com.meetingatlas.notes

import androidx.room.withTransaction
import com.meetingatlas.ai.OpenRouterClient
import com.meetingatlas.ai.OpenRouterSettingsStore
import com.meetingatlas.data.*
import com.meetingatlas.recording.AudioWorkCoordinator
import com.meetingatlas.recording.RecordingFileManager
import com.meetingatlas.recording.RecordingPreferences
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

enum class PipelineStep { CONTINUE, DONE, DEFER }

@Singleton
class NotesPipeline @Inject constructor(
    private val db: MeetingDatabase, private val settings: OpenRouterSettingsStore, private val client: OpenRouterClient,
    private val engine: OpenRouterNotesEngine, private val slices: AudioSlicePreparer, private val repository: NotesRepository,
    private val files: RecordingFileManager, private val preferences: RecordingPreferences,
) {
    private val dao get() = db.notesDao()
    suspend fun prepare(meetingId: String, template: MeetingTemplate, topicId: String? = null): String = db.withTransaction {
        val meeting = requireNotNull(db.meetingDao().findById(meetingId)) { "Meeting no longer exists" }
        check(meeting.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED)) { "Save the recording before processing" }
        val jobId = if (topicId == null) "notes-$meetingId" else "topic-$topicId"
        val existingState = dao.state(meetingId)
        val existingJob = dao.job(jobId)
        if (existingState == null) {
            val legacy = db.meetingAnalysisDao().find(meetingId)
            if (!legacy?.transcript.isNullOrBlank()) {
                val chunks = legacy!!.transcript.chunked(TranscriptWindows.MAX_SOURCE_CHARS)
                dao.saveSources(chunks.mapIndexedNotNull { index, text -> if (text.isBlank()) null else TranscriptSegmentEntity("$meetingId:legacy:$index", meetingId, index, text, null, null, "UNKNOWN") })
                // Move canonical evidence rather than copying the full transcript into two stores.
                db.meetingAnalysisDao().clearMigratedTranscript(meetingId)
            }
            val plans = if (dao.sourceCount(meetingId) > 0) emptyList() else {
                var position = 0
                db.audioSegmentDao().findForMeeting(meetingId).filter { it.durationMs > 0 && it.validationStatus in setOf("FINALIZED", "VALIDATED", "RECOVERED") }.flatMap { segment ->
                    val file = File(segment.localPath)
                    check(file.isFile) { "A source audio file is missing. Existing content has been kept." }
                    val durationUs = segment.durationMs * 1000
                    generateSequence(0L) { it + CHUNK_US }.takeWhile { it < durationUs }.map { start ->
                        val index = position++
                        ProcessingChunkEntity("$meetingId:chunk:$index", meetingId, index, file.absolutePath, file.length(), file.lastModified(), segment.startOffsetMs, start, minOf(start + CHUNK_US, durationUs), "PENDING")
                    }.toList()
                }
            }
            check(plans.isNotEmpty() || dao.sourceCount(meetingId) > 0) { "No transcript or validated source audio is available." }
            dao.saveChunks(plans)
            dao.saveState(NotesStateEntity(meetingId, "QUEUED", template.name, 0, plans.size, 0, -1, legacy?.audioDeletedAt, System.currentTimeMillis()))
        } else if (topicId == null) {
            if (existingState.status == "NO_SPEECH" && existingJob?.status == "SUCCEEDED") {
                check(dao.sourceCount(meetingId) == 0) { "Saved transcript exists. Resume its processing instead." }
                dao.resetChunks(meetingId)
                check(dao.nextChunk(meetingId) != null) { "Source audio is unavailable for another transcription attempt." }
                dao.saveState(existingState.copy(status = "QUEUED", transcribedChunks = 0, updatedAt = System.currentTimeMillis()))
            } else check(existingJob?.status != "SUCCEEDED") { "Notes already exist. Refresh an individual topic to preserve existing work." }
        }
        if (topicId != null) {
            check(dao.topic(topicId)?.meetingId == meetingId) { "This topic belongs to another meeting" }
            check(existingState?.status in setOf("FINALIZED", "PARTIAL_AUDIO")) { "Finish the original notes before refreshing a topic" }
        }
        val job = if (existingJob != null && existingJob.status != "SUCCEEDED") existingJob.copy(status = "QUEUED", textModel = settings.model(), transcriptionModel = settings.transcriptionModel(), error = null, updatedAt = System.currentTimeMillis()) else NotesJobEntity(jobId, meetingId, topicId, "QUEUED", -1, existingState?.template ?: template.name, settings.model(), settings.transcriptionModel(), null, System.currentTimeMillis())
        dao.saveJob(job)
        jobId
    }

    suspend fun step(jobId: String): PipelineStep {
        if (!AudioWorkCoordinator.startProcessing()) return PipelineStep.DEFER
        return try { stepOwned(jobId) } finally { AudioWorkCoordinator.finishProcessing() }
    }

    private suspend fun stepOwned(jobId: String): PipelineStep {
        val job = dao.job(jobId) ?: return PipelineStep.DONE
        if (job.status == "CANCELLED") return PipelineStep.DONE
        if (job.status == "SUCCEEDED") { cleanupAudio(job); return PipelineStep.DONE }
        val state = dao.state(job.meetingId) ?: return PipelineStep.DONE
        currentCoroutineContext().ensureActive()
        val available = if (job.topicId == null) dao.sources(job.meetingId, job.cursor) else dao.topicSources(job.topicId, job.cursor)
        val chunk = if (job.topicId == null) dao.nextChunk(job.meetingId) else null
        if (chunk != null && available.isEmpty()) {
            val key = providerKey()
            dao.saveJob(job.copy(status = "TRANSCRIBING", error = null, updatedAt = System.currentTimeMillis()))
            dao.saveState(state.copy(status = "TRANSCRIBING", updatedAt = System.currentTimeMillis()))
            val prepared = slices.prepare(chunk)
            try {
                val raw = client.transcribeTimed(prepared.file, key, job.transcriptionModel)
                val transcript = TranscriptNormalizer.normalize(raw, job.meetingId, chunk.position, prepared.globalStartMs, prepared.durationMs)
                currentCoroutineContext().ensureActive()
                db.withTransaction {
                    if (dao.job(jobId)?.status == "CANCELLED") throw kotlinx.coroutines.CancellationException("Processing cancelled")
                    dao.saveSources(transcript.map { TranscriptSegmentEntity(it.id, job.meetingId, it.order, it.text, it.startMs, it.endMs, it.precision) })
                    dao.completeChunk(chunk.id)
                    val current = requireNotNull(dao.state(job.meetingId))
                    dao.saveState(current.copy(transcribedChunks = current.transcribedChunks + 1, updatedAt = System.currentTimeMillis()))
                    dao.saveJob(job.copy(status = "RUNNING", error = null, updatedAt = System.currentTimeMillis()))
                }
            } finally { prepared.file.delete(); prepared.file.parentFile?.delete() }
            return PipelineStep.CONTINUE
        }
        val window = TranscriptWindows.next(available.map { it.asSource() })
        if (window.isNotEmpty()) {
            val key = providerKey()
            val allTopics = dao.topics(job.meetingId)
            val topic = job.topicId?.let { requireNotNull(dao.topic(it)) }
            val sourceText = window.joinToString("\n") { it.text }
            val selectedTopics = if (topic != null) listOf(topic) else KnowledgeContext.topics(allTopics, sourceText)
            val knownTopics = selectedTopics.map { TopicPatch(it.topicKey, it.title) }
            val allEntries = dao.entries(job.meetingId)
            val prior = KnowledgeContext.entries(allEntries, selectedTopics, sourceText)
            dao.saveJob(job.copy(status = "EXTRACTING", error = null, updatedAt = System.currentTimeMillis()))
            val template = MeetingTemplate.valueOf(job.template)
            val extraction = engine.extract(ExtractionInput(template.label, template.focus, window, knownTopics, prior), key, job.textModel, topic?.topicKey)
            currentCoroutineContext().ensureActive()
            // Source and entry checks are repeated against current local state inside the merge transaction.
            repository.apply(job, window, extraction, window.last().order)
            return PipelineStep.CONTINUE
        }
        currentCoroutineContext().ensureActive()
        val partialAudio = db.meetingDao().findById(job.meetingId)?.recordingStatus == RecordingStatus.INTERRUPTED
        if (job.topicId == null) check(dao.coverageCount(job.meetingId) == dao.sourceCount(job.meetingId)) { "Some transcript segments still need source-grounded notes. Saved progress was kept." }
        val noSpeech = dao.sourceCount(job.meetingId) == 0
        val finalStatus = if (noSpeech) "NO_SPEECH" else if (partialAudio) "PARTIAL_AUDIO" else "FINALIZED"
        db.withTransaction {
            if (dao.job(jobId)?.status == "CANCELLED") throw kotlinx.coroutines.CancellationException("Processing cancelled")
            val current = requireNotNull(dao.state(job.meetingId))
            val revision = current.revision + 1
            dao.saveJob(job.copy(status = "SUCCEEDED", error = null, updatedAt = System.currentTimeMillis()))
            dao.saveState(current.copy(status = finalStatus, revision = revision, updatedAt = System.currentTimeMillis()))
            dao.snapshot(SummarySnapshotEntity("${job.meetingId}:$revision", job.meetingId, revision, current.summarizedOrder, finalStatus, System.currentTimeMillis()))
        }
        cleanupAudio(job)
        return PipelineStep.DONE
    }

    private suspend fun cleanupAudio(job: NotesJobEntity) {
        val state = dao.state(job.meetingId) ?: return
        if (job.topicId == null && state.status == "FINALIZED" && state.audioDeletedAt == null && dao.entries(job.meetingId).isNotEmpty() && preferences.options.first().deleteAfterNotes) {
            com.meetingatlas.playback.PlaybackInvalidation.invalidate(job.meetingId)
            if (files.deleteMeetingAudio(job.meetingId, files.segments(job.meetingId))) db.withTransaction {
                db.meetingDao().clearAudioPath(job.meetingId, System.currentTimeMillis())
                db.audioSegmentDao().deleteForMeeting(job.meetingId)
                db.meetingAnalysisDao().markAudioDeleted(job.meetingId, System.currentTimeMillis())
                val current = requireNotNull(dao.state(job.meetingId))
                dao.saveState(current.copy(audioDeletedAt = System.currentTimeMillis()))
            } else dao.job(job.id)?.let { dao.saveJob(it.copy(error = "Notes are saved, but source audio could not be removed. Use Remove source audio to retry.")) }
        }
    }

    suspend fun markFailure(jobId: String, message: String, status: String = "FAILED") {
        dao.job(jobId)?.let { dao.saveJob(it.copy(status = status, error = message.take(400), updatedAt = System.currentTimeMillis())) }
    }
    private fun providerKey() = settings.apiKey()?.takeIf { it.isNotBlank() } ?: error("Add an OpenRouter key in Settings, then retry. Saved progress is safe.")
    companion object { private const val CHUNK_US = 120_000_000L }
}
