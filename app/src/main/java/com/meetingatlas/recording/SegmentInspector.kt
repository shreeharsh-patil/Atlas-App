package com.meetingatlas.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.meetingatlas.data.AudioSegmentEntity
import java.io.File
import javax.inject.Inject

data class SegmentCheck(val durationMs: Long, val bytes: Long, val valid: Boolean)

/** Reads container metadata and an encoded sample, never decodes or buffers the whole file. */
class SegmentInspector @Inject constructor() {
    fun inspect(file: File): SegmentCheck {
        if (!file.isFile || file.length() < 128) return SegmentCheck(0, file.length(), false)
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return SegmentCheck(0, file.length(), false)
            extractor.selectTrack(track)
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            SegmentCheck(duration, file.length(), duration > 0 && extractor.sampleTime >= 0 && (android.os.Build.VERSION.SDK_INT < 28 || extractor.sampleSize > 0))
        } catch (_: Exception) {
            SegmentCheck(0, file.length(), false)
        } finally { extractor.release(); retriever.release() }
    }
}

/** Idempotent identifiers and a continuous timeline containing only validated media. */
fun segmentEntry(meetingId: String, sequence: Int, path: String, check: SegmentCheck, offset: Long, recovered: Boolean = false) = AudioSegmentEntity(
    id = "$meetingId:$sequence", meetingId = meetingId, sequence = sequence, localPath = path,
    startOffsetMs = offset, durationMs = if (check.valid) check.durationMs else 0, fileSize = check.bytes,
    validationStatus = if (!check.valid) "INVALID" else if (recovered) "RECOVERED" else "VALIDATED",
)
