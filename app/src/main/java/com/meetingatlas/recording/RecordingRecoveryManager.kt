package com.meetingatlas.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import com.meetingatlas.data.AudioSegmentDao
import com.meetingatlas.data.AudioSegmentEntity
import com.meetingatlas.data.MeetingDao
import com.meetingatlas.data.RecordingStatus
import java.io.File
import java.util.UUID

class RecordingRecoveryManager(
    context: Context,
    private val meetings: MeetingDao,
    private val segments: AudioSegmentDao,
) {
    private val root = File(context.filesDir, "recordings")

    suspend fun recoverInterruptedSessions() {
        if (MeetingRecordingService.serviceAlive) return
        for (meeting in meetings.unfinished()) {
            val candidates = File(root, meeting.id).listFiles { file -> file.extension == "m4a" }.orEmpty().sortedBy { it.name }
            var offset = 0L
            val valid = candidates.mapIndexedNotNull { index, file ->
                val duration = durationOf(file)
                if (duration == null || file.length() <= 1024) return@mapIndexedNotNull null
                AudioSegmentEntity(UUID.randomUUID().toString(), meeting.id, index + 1, file.absolutePath, offset, duration, file.length(), "RECOVERED")
                    .also { offset += duration }
            }
            if (valid.isNotEmpty()) segments.insertAll(valid)
            meetings.finish(
                meeting.id,
                RecordingStatus.INTERRUPTED,
                maxOf(meeting.durationMs, offset),
                valid.firstOrNull()?.localPath,
                System.currentTimeMillis(),
                "Recording ended unexpectedly. Recoverable audio has been kept.",
            )
        }
    }

    private fun durationOf(file: File): Long? = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull().also { release() }
        }
    } catch (_: Exception) { null }
}
