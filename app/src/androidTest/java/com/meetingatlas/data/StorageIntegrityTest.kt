package com.meetingatlas.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meetingatlas.di.StorageModule
import com.meetingatlas.recording.RecordingRecoveryManager
import com.meetingatlas.recording.RecordingFileManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageIntegrityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MeetingDatabase::class.java)
    private val databaseName = "v2-storage-test"
    private fun open() = Room.databaseBuilder(context, MeetingDatabase::class.java, databaseName).addMigrations(*StorageModule.ALL_MIGRATIONS).build()
    private suspend fun <T> withDatabase(block: suspend (MeetingDatabase) -> T): T {
        val db = open()
        return try { block(db) } finally { db.close() }
    }
    @After fun clean() { context.deleteDatabase(databaseName) }

    @Test fun migrateVersionOnePreservesCatalogAndAudioSegments() {
        migrations.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO meetings (id,title,createdAt,durationMs,recordingStatus,pinned,updatedAt) VALUES ('legacy','Existing meeting',1,2000,'SAVED',0,1)")
            execSQL("INSERT INTO audio_segments (id,meetingId,sequence,localPath,startOffsetMs,durationMs,fileSize,validationStatus) VALUES ('segment','legacy',1,'/existing.m4a',0,2000,4096,'FINALIZED')")
            close()
        }
        migrations.runMigrationsAndValidate(databaseName, 3, true, StorageModule.MIGRATION_1_2, StorageModule.MIGRATION_2_3).use { db ->
            db.query("SELECT title FROM meetings WHERE id='legacy'").use { assertTrue(it.moveToFirst()); assertEquals("Existing meeting", it.getString(0)) }
            db.query("SELECT durationMs FROM audio_segments WHERE id='segment'").use { assertTrue(it.moveToFirst()); assertEquals(2000, it.getLong(0)) }
        }
    }

    @Test fun migrateVersionTwoPreservesSavedTranscriptAndNotes() {
        migrations.createDatabase(databaseName, 2).apply {
            execSQL("INSERT INTO meetings (id,title,createdAt,durationMs,recordingStatus,pinned,updatedAt) VALUES ('legacy','Saved notes',1,2000,'SAVED',0,1)")
            execSQL("INSERT INTO meeting_analysis (meetingId,transcript,summary,keyPointsJson,decisionsJson,actionItemsJson,mindMapJson,generatedAt) VALUES ('legacy','Original evidence','User notes','[]','[]','[]','{}',1)")
            close()
        }
        migrations.runMigrationsAndValidate(databaseName, 3, true, StorageModule.MIGRATION_2_3).use { db ->
            db.query("SELECT transcript,summary FROM meeting_analysis").use { assertTrue(it.moveToFirst()); assertEquals("Original evidence", it.getString(0)); assertEquals("User notes", it.getString(1)) }
        }
    }

    @Test fun migrateVersionThreeToFourPreservesOfflineContent() {
        migrations.createDatabase(databaseName, 3).apply {
            execSQL("INSERT INTO meetings (id,title,createdAt,durationMs,recordingStatus,pinned,updatedAt) VALUES ('v3','Saved meeting',1,2000,'SAVED',0,1)")
            execSQL("INSERT INTO meeting_analysis (meetingId,transcript,summary,keyPointsJson,decisionsJson,actionItemsJson,mindMapJson,generatedAt) VALUES ('v3','Original transcript','Existing notes','[]','[]','[]','{}',1)")
            close()
        }
        migrations.runMigrationsAndValidate(databaseName, 4, true, *StorageModule.ALL_MIGRATIONS).use { db ->
            db.query("SELECT transcript,summary FROM meeting_analysis").use { assertTrue(it.moveToFirst()); assertEquals("Original transcript", it.getString(0)); assertEquals("Existing notes", it.getString(1)) }
        }
    }

    @Test fun updateAndReopenPreservesChildrenAndPlaybackPosition() = runBlocking {
        withDatabase { db ->
            db.meetingDao().upsert(MeetingEntity("persist", "Initial title", 1))
            db.audioSegmentDao().insertAll(listOf(AudioSegmentEntity("part", "persist", 1, "/file.m4a", 0, 1000, 4096)))
            db.recordingJournalDao().bookmark(BookmarkEntity("mark", "persist", 400, "Question"))
            db.recordingJournalDao().savePlayback(PlaybackPositionEntity("persist", 700, 1.5f))
            db.meetingAnalysisDao().save(MeetingAnalysisEntity("persist", "Evidence", "Notes", "[]", "[]", "[]", "{}", 1))
            db.meetingDao().upsert(db.meetingDao().findById("persist")!!.copy(title = "Renamed"))
        }
        withDatabase { db ->
            assertEquals("Renamed", db.meetingDao().findById("persist")!!.title)
            assertEquals(1, db.audioSegmentDao().findForMeeting("persist").size)
            assertEquals("Evidence", db.meetingAnalysisDao().find("persist")!!.transcript)
            assertEquals(700, db.recordingJournalDao().playback("persist")!!.positionMs)
            db.openHelper.readableDatabase.query("SELECT label FROM bookmarks WHERE id='mark'").use { assertTrue(it.moveToFirst()); assertEquals("Question", it.getString(0)) }
        }
    }

    @Test fun recoveryRetainsDamagedFileAndDoesNotOfferItForPlayback() = runBlocking {
        val id = UUID.randomUUID().toString()
        val files = RecordingFileManager(context)
        val corrupt = files.segment(id, 1).apply { writeText("unfinished mp4") }
        try {
            withDatabase { db ->
                db.meetingDao().upsert(MeetingEntity(id, "Interrupted", 1, recordingStatus = RecordingStatus.RECORDING))
                RecordingRecoveryManager(context, db).recoverInterruptedSessions()
                assertEquals(RecordingStatus.FAILED, db.meetingDao().findById(id)!!.recordingStatus)
                val segment = db.audioSegmentDao().findForMeeting(id).single()
                assertEquals("INVALID", segment.validationStatus)
                assertEquals(0, segment.durationMs)
                assertNull(db.meetingDao().findById(id)!!.audioPath)
                assertTrue(corrupt.exists())
                // A second recovery cannot create duplicate rows or modify evidence.
                RecordingRecoveryManager(context, db).recoverInterruptedSessions()
                assertEquals(1, db.audioSegmentDao().findForMeeting(id).size)
            }
        } finally { corrupt.delete(); corrupt.parentFile?.delete() }
    }

    @Test fun reopeningAnUnstartedDraftDoesNotTurnItIntoAFailedRecording() = runBlocking {
        withDatabase { db ->
            db.meetingDao().upsert(MeetingEntity("unstarted-draft", "Ready", 1))
            RecordingRecoveryManager(context, db).recoverInterruptedSessions()
            assertEquals(RecordingStatus.PREPARING, db.meetingDao().findById("unstarted-draft")!!.recordingStatus)
        }
    }
}
