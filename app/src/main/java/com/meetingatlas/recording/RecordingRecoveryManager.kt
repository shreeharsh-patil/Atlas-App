package com.meetingatlas.recording

import android.content.Context
import androidx.room.withTransaction
import com.meetingatlas.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordingRecoveryManager(context: Context, private val db: MeetingDatabase) {
    private val files = RecordingFileManager(context.applicationContext)
    private val inspector = SegmentInspector()

    suspend fun recoverInterruptedSessions() = withContext(Dispatchers.IO) {
        if (!AudioWorkCoordinator.startRecovery()) return@withContext
        try {
            for (meeting in db.meetingDao().unfinished()) {
                val candidates = files.segments(meeting.id)
                val previous = db.recordingJournalDao().session(meeting.id)
                // A prepared UI draft is not an interrupted capture. Rotation/reopening must keep it startable.
                if (meeting.recordingStatus == RecordingStatus.PREPARING && meeting.startedAt == null && previous == null && candidates.isEmpty()) continue
                var offset = 0L
                val entries = candidates.map { file ->
                    val number = file.name.substringAfter("segment-").substringBefore('.').toInt()
                    val check = inspector.inspect(file)
                    segmentEntry(meeting.id, number, file.absolutePath, check, offset, recovered = true).also { offset += it.durationMs }
                }
                val valid = entries.filter { it.durationMs > 0 }
                db.withTransaction {
                    db.audioSegmentDao().deleteForMeeting(meeting.id)
                    if (entries.isNotEmpty()) db.audioSegmentDao().insertAll(entries)
                    db.meetingDao().finish(meeting.id, if (valid.isEmpty()) RecordingStatus.FAILED else RecordingStatus.INTERRUPTED, offset, valid.firstOrNull()?.localPath, System.currentTimeMillis(),
                        if (valid.isEmpty()) "The session ended before playable audio was finalized. Incomplete files were kept." else "Recording ended unexpectedly. Playable segments were recovered; an unfinished segment may be missing.")
                    db.recordingJournalDao().saveSession((previous ?: RecordingSessionEntity(meeting.id, "BALANCED", "INTERRUPTED", updatedAt = System.currentTimeMillis())).copy(state = "INTERRUPTED", elapsedMs = offset, updatedAt = System.currentTimeMillis()))
                    db.recordingJournalDao().event(RecordingEventEntity(meetingId = meeting.id, type = "RECOVERED", detail = "valid=${valid.size}; invalid=${entries.size - valid.size}; duration=$offset"))
                }
            }
        } finally { AudioWorkCoordinator.finishRecovery() }
    }
}
