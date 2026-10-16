package com.meetingatlas.recording

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meetingatlas.MainActivity
import com.meetingatlas.data.*
import com.meetingatlas.di.StorageModule
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Captures actual platform-encoded audio; no cloud request or real meeting content is involved. */
@RunWith(AndroidJUnit4::class)
class RecordingFlowTest {
    @Test fun recordPauseBookmarkSaveReopenAndRecoverValidSegment() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val database = Room.databaseBuilder(context, MeetingDatabase::class.java, "meeting-atlas.db").addMigrations(*StorageModule.ALL_MIGRATIONS).build()
        val id = UUID.randomUUID().toString()
        val files = RecordingFileManager(context)
        fun control(action: String) { instrumentation.runOnMainSync { context.startService(Intent(context, MeetingRecordingService::class.java).setAction(action)) } }
        try {
            delay(500)
            database.meetingDao().upsert(MeetingEntity(id, "Recording integration check", System.currentTimeMillis()))
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, MeetingRecordingService::class.java).setAction(MeetingRecordingService.ACTION_START).putExtra(MeetingRecordingService.EXTRA_MEETING_ID, id)) }
            withTimeout(20000) { RecordingProcessState.state.first { it is RecordingState.Recording && it.meetingId == id } }
            delay(2500)
            control(MeetingRecordingService.ACTION_PAUSE)
            val paused = withTimeout(10000) { RecordingProcessState.state.first { it is RecordingState.Paused && it.meetingId == id } } as RecordingState.Paused
            delay(1000)
            control(MeetingRecordingService.ACTION_BOOKMARK)
            val mark = withTimeout(10000) { database.recordingJournalDao().bookmarks(id).first { it.isNotEmpty() } }.single()
            assertEquals(paused.elapsedMs, mark.elapsedMs)
            control(MeetingRecordingService.ACTION_RESUME)
            withTimeout(10000) { RecordingProcessState.state.first { it is RecordingState.Recording && it.meetingId == id } }
            delay(1500)
            control(MeetingRecordingService.ACTION_STOP)
            control(MeetingRecordingService.ACTION_STOP)
            withTimeout(20000) { RecordingProcessState.state.first { it is RecordingState.Saved && it.meetingId == id } }
            val saved = database.meetingDao().findById(id)!!
            assertEquals(RecordingStatus.SAVED, saved.recordingStatus)
            assertTrue(saved.durationMs > 0)
            assertTrue(database.audioSegmentDao().findForMeeting(id).all { it.validationStatus == "VALIDATED" && it.durationMs > 0 })
            // Reconstruct after an interruption with one healthy file and an unfinished tail.
            val corrupt = files.segment(id, 2).apply { writeText("unfinished mp4 tail") }
            database.meetingDao().upsert(saved.copy(recordingStatus = RecordingStatus.RECORDING))
            RecordingRecoveryManager(context, database).recoverInterruptedSessions()
            val recovered = database.meetingDao().findById(id)!!
            assertEquals(RecordingStatus.INTERRUPTED, recovered.recordingStatus)
            assertNotNull(recovered.audioPath)
            assertEquals(saved.durationMs, recovered.durationMs)
            assertEquals(2, database.audioSegmentDao().findForMeeting(id).size)
            assertTrue(corrupt.exists())
            assertTrue(SegmentInspector().inspect(java.io.File(recovered.audioPath!!)).valid)
        } finally {
            if (AudioWorkCoordinator.recording.value == id) {
                control(MeetingRecordingService.ACTION_STOP)
                withTimeoutOrNull(10000) { AudioWorkCoordinator.recording.first { it == null } }
            }
            files.deleteMeetingAudio(id, files.segments(id))
            database.meetingDao().delete(id)
            database.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
