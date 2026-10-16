package com.meetingatlas.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meetingatlas.ai.OpenRouterClient
import com.meetingatlas.ai.OpenRouterSettingsStore
import com.meetingatlas.di.StorageModule
import com.meetingatlas.notes.*
import com.meetingatlas.recording.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "v3-notes-test"
    private val meetingId = UUID.randomUUID().toString()
    private lateinit var db: MeetingDatabase
    private lateinit var notes: NotesRepository
    private val speech = "We agreed to use Room to store meeting evidence locally."
    private fun open() = Room.databaseBuilder(context, MeetingDatabase::class.java, databaseName).addMigrations(*StorageModule.ALL_MIGRATIONS).build()
    @Before fun setup() { db = open(); notes = NotesRepository(db) }
    @After fun clean() { db.close(); context.deleteDatabase(databaseName); RecordingFileManager(context).let { it.deleteMeetingAudio(meetingId, it.segments(meetingId)) } }
    private fun pipeline(): NotesPipeline {
        val client = OpenRouterClient()
        val files = RecordingFileManager(context)
        return NotesPipeline(db, OpenRouterSettingsStore(context), client, OpenRouterNotesEngine(client), AudioSlicePreparer(context, files, SegmentInspector()), notes, files, RecordingPreferences(context))
    }
    private suspend fun seed(): NotesJobEntity {
        db.meetingDao().upsert(MeetingEntity(meetingId, "Evidence fixture", 1, recordingStatus = RecordingStatus.SAVED))
        db.notesDao().saveState(NotesStateEntity(meetingId, "QUEUED", "GENERAL", 0, 0, 0, -1, null, 1))
        db.notesDao().saveSources(listOf(TranscriptSegmentEntity("source", meetingId, 0, speech, 1000, 6000, "PROVIDER_SEGMENT")))
        return NotesJobEntity("job", meetingId, null, "RUNNING", -1, "GENERAL", "openrouter/free", "openai/whisper-large-v3-turbo", null, 1).also { db.notesDao().saveJob(it) }
    }
    private fun result(text: String = "Room will store meeting evidence locally.") = WindowExtraction(listOf(TopicPatch("storage", "Storage")), listOf(EntryPatch("use_room", "storage", NoteSection.DECISION, text, 3, listOf(EvidenceRef("source", speech)), null, null, false, null)), emptyList(), emptyList())
    private fun sources() = listOf(EvidenceSource("source", 0, speech, 1000, 6000, "PROVIDER_SEGMENT"))

    @Test fun retryCannotDuplicateNotesEvidenceOrAdvanceASnapshotTwice() = runBlocking {
        val job = seed()
        notes.apply(job, sources(), result(), 0)
        notes.apply(job, sources(), result(), 0)
        assertEquals(1, db.notesDao().entries(meetingId).size)
        assertEquals(1, db.notesDao().coverageCount(meetingId))
        assertEquals(1, db.notesDao().snapshots(meetingId).first().size)
        assertEquals(0, db.notesDao().job(job.id)!!.cursor)
        assertEquals(1, db.notesDao().evidenceForEntry(db.notesDao().entries(meetingId).single().id).size)
    }
    @Test fun manualRevisionSurvivesAiRefreshAndCandidateRemainsInHistory() = runBlocking {
        val job = seed(); notes.apply(job, sources(), result(), 0)
        val entry = db.notesDao().entries(meetingId).single()
        notes.edit(entry.id, entry.revision, "My reviewed description of the Room decision.")
        val refresh = job.copy(id = "refresh", topicId = entry.topicId, cursor = -1)
        db.notesDao().saveJob(refresh)
        notes.apply(refresh, sources(), result("Use Room for durable local meeting evidence."), 0)
        val current = db.notesDao().entry(entry.id)!!
        assertEquals("My reviewed description of the Room decision.", current.text)
        assertTrue(current.userEdited)
        val history = db.notesDao().history(entry.id).first()
        assertTrue(history.any { it.origin == "AI_CANDIDATE" && !it.applied })
        assertTrue(history.any { it.origin == "USER" && it.applied })
    }
    @Test fun invalidOutputRollsBackCursorAndKeepsPriorNotes() = runBlocking {
        val job = seed()
        val invalid = result().copy(entries = result().entries.map { it.copy(sources = listOf(EvidenceRef("source", "We agreed to publish next Monday."))) })
        try { notes.apply(job, sources(), invalid, 0); fail("Invalid source quote accepted") } catch (_: InvalidNotes) { }
        assertEquals(-1, db.notesDao().job(job.id)!!.cursor)
        assertEquals(0, db.notesDao().entries(meetingId).size)
        assertEquals(0, db.notesDao().state(meetingId)!!.revision)
    }
    @Test fun notesEvidenceCollapseAndCheckpointSurviveDatabaseReopen() = runBlocking {
        val job = seed(); notes.apply(job, sources(), result(), 0)
        val topic = db.notesDao().topics(meetingId).single()
        notes.collapse(topic.id, true)
        val entryId = db.notesDao().entries(meetingId).single().id
        db.close(); db = open(); notes = NotesRepository(db)
        val reopened = notes.document(meetingId).first()!!
        assertTrue(reopened.topics.single().collapsed)
        assertEquals(entryId, reopened.entries.single().id)
        assertEquals(speech, notes.evidence(entryId).single().text)
        assertEquals(0, db.notesDao().job(job.id)!!.cursor)
    }
    @Test fun staleEditorCannotOverwriteALaterManualRevision() = runBlocking {
        val job = seed(); notes.apply(job, sources(), result(), 0)
        val entry = db.notesDao().entries(meetingId).single()
        notes.edit(entry.id, entry.revision, "First manual revision")
        try { notes.edit(entry.id, entry.revision, "Stale edit"); fail("Stale editor replaced a newer revision") } catch (_: IllegalStateException) { }
        assertEquals("First manual revision", db.notesDao().entry(entry.id)!!.text)
    }
    @Test fun unappliedAiCandidateCannotSupersedeAnotherDecision() = runBlocking {
        val job = seed(); notes.apply(job, sources(), result(), 0)
        val original = db.notesDao().entries(meetingId).single()
        val refresh = job.copy(id = "refresh", topicId = original.topicId, cursor = -1)
        db.notesDao().saveJob(refresh)
        val second = result().copy(entries = result().entries.map { it.copy(key = "second_decision", text = "A separately recorded storage choice.") })
        notes.apply(refresh, sources(), second, 0)
        val reviewed = db.notesDao().entries(meetingId).first { it.entryKey == "second_decision" }
        notes.edit(reviewed.id, reviewed.revision, "My reviewed second decision.")
        val later = refresh.copy(id = "later", cursor = -1)
        db.notesDao().saveJob(later)
        notes.apply(later, sources(), second.copy(entries = second.entries.map { it.copy(text = "AI candidate replacement", supersedesKey = original.entryKey) }), 0)
        assertNull(db.notesDao().entry(original.id)!!.supersededBy)
        assertEquals("My reviewed second decision.", db.notesDao().entry(reviewed.id)!!.text)
    }
    @Test fun pausedTopicRefreshKeepsFinalMeetingEligibleForResume() = runBlocking {
        val job = seed(); notes.apply(job, sources(), result(), 0)
        val state = db.notesDao().state(meetingId)!!
        db.notesDao().saveState(state.copy(status = "FINALIZED"))
        val refresh = job.copy(id = "topic-refresh", topicId = db.notesDao().topics(meetingId).single().id, cursor = -1)
        db.notesDao().saveJob(refresh)
        notes.apply(refresh, sources(), result("Updated storage description."), 0)
        assertEquals("FINALIZED", db.notesDao().state(meetingId)!!.status)
    }
    @Test fun finalLocalCommitPrecedesAutomaticAudioRemovalAndKeepsEvidence() = runBlocking {
        val preferences = RecordingPreferences(context)
        val originalRetention = preferences.options.first().deleteAfterNotes
        try {
            preferences.setDeleteAfterNotes(true)
            val job = seed(); notes.apply(job, sources(), result(), 0)
            val source = RecordingFileManager(context).segment(meetingId, 1).apply { writeText("private test source bytes") }
            db.meetingDao().upsert(db.meetingDao().findById(meetingId)!!.copy(audioPath = source.absolutePath))
            assertEquals(PipelineStep.DONE, pipeline().step(job.id))
            assertFalse(source.exists())
            assertEquals("FINALIZED", db.notesDao().state(meetingId)!!.status)
            assertNotNull(db.notesDao().state(meetingId)!!.audioDeletedAt)
            assertNull(db.meetingDao().findById(meetingId)!!.audioPath)
            assertEquals(speech, db.notesDao().sources(meetingId).single().text)
            assertEquals(1, notes.evidence(db.notesDao().entries(meetingId).single().id).size)
        } finally { preferences.setDeleteAfterNotes(originalRetention) }
    }
    @Test fun missingSourceCoverageCannotFinalizeOrDeleteAudio() = runBlocking {
        val job = seed()
        db.notesDao().saveJob(job.copy(cursor = 0))
        val source = RecordingFileManager(context).segment(meetingId, 1).apply { writeText("test source retained on failure") }
        try { pipeline().step(job.id); fail("Unaccounted source finalized") } catch (_: IllegalStateException) { }
        assertTrue(source.exists())
        assertNull(db.notesDao().state(meetingId)!!.audioDeletedAt)
        assertEquals("QUEUED", db.notesDao().state(meetingId)!!.status)
    }
    @Test fun noSpeechRetryResetsOnlyAudioCheckpointsAndKeepsSource() = runBlocking {
        val source = RecordingFileManager(context).segment(meetingId, 1).apply { writeText("test retry source") }
        db.meetingDao().upsert(MeetingEntity(meetingId, "No speech fixture", 1, recordingStatus = RecordingStatus.SAVED, audioPath = source.absolutePath))
        db.notesDao().saveState(NotesStateEntity(meetingId, "NO_SPEECH", "GENERAL", 1, 1, 1, -1, null, 1))
        db.notesDao().saveChunks(listOf(ProcessingChunkEntity("chunk", meetingId, 0, source.absolutePath, source.length(), source.lastModified(), 0, 0, 1000000, "COMPLETE")))
        db.notesDao().saveJob(NotesJobEntity("notes-$meetingId", meetingId, null, "SUCCEEDED", -1, "GENERAL", "openrouter/free", "openai/whisper-large-v3-turbo", null, 1))
        val jobId = pipeline().prepare(meetingId, MeetingTemplate.GENERAL)
        assertEquals("QUEUED", db.notesDao().job(jobId)!!.status)
        assertEquals(0, db.notesDao().state(meetingId)!!.transcribedChunks)
        assertNotNull(db.notesDao().nextChunk(meetingId))
        assertTrue(source.exists())
    }
    @Test fun legacyUpgradeMovesTranscriptWithoutInventingTimesOrLosingOldNotes() = runBlocking {
        db.meetingDao().upsert(MeetingEntity(meetingId, "Legacy meeting", 1, recordingStatus = RecordingStatus.SAVED))
        val original = "Legacy evidence. ".repeat(1000)
        db.meetingAnalysisDao().save(MeetingAnalysisEntity(meetingId, original, "Existing saved notes", "[]", "[]", "[]", "{}", 1, 2))
        pipeline().prepare(meetingId, MeetingTemplate.GENERAL)
        val stored = db.notesDao().sources(meetingId)
        assertEquals(original, stored.joinToString("") { it.text })
        assertTrue(stored.all { it.startMs == null && it.endMs == null && it.precision == "UNKNOWN" })
        assertEquals("", db.meetingAnalysisDao().find(meetingId)!!.transcript)
        assertEquals("Existing saved notes", db.meetingAnalysisDao().find(meetingId)!!.summary)
    }
}
