package com.meetingatlas.notes

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.meetingatlas.data.ProcessingChunkEntity
import com.meetingatlas.recording.RecordingFileManager
import com.meetingatlas.recording.SegmentInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

data class PreparedAudioSlice(val file: File, val globalStartMs: Long, val durationMs: Long)

/** One compressed chunk on disk at a time. No PCM decoding, whole-file buffering or capture. */
class AudioSlicePreparer @Inject constructor(@param:ApplicationContext private val context: Context, private val files: RecordingFileManager, private val inspector: SegmentInspector) {
    fun prepare(chunk: ProcessingChunkEntity): PreparedAudioSlice {
        val source = File(chunk.sourcePath).canonicalFile
        require(files.segments(chunk.meetingId).any { it.canonicalFile == source }) { "Audio is outside managed meeting storage" }
        // The file size is checked, not the modification time: a timestamp has one-second
        // granularity on some filesystems and can change without the audio changing, which
        // would permanently fail every chunk of an intact recording.
        require(source.isFile && source.length() == chunk.sourceSize) { "Source audio is missing or truncated. Saved transcripts and notes were kept." }
        val directory = File(context.cacheDir, "notes-v3/${chunk.meetingId}").apply { check(exists() || mkdirs()) }
        // Sweep leftovers from a previous run that was killed mid-transcription.
        directory.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(directory, "slice-${chunk.position}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var firstSampleUs = -1L
        var wrote = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC } ?: error("No compatible AAC audio track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(chunk.startUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val output = muxer.addTrack(format)
            muxer.start(); started = true
            while (extractor.sampleTime >= 0 && extractor.sampleTime < chunk.endUs) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity()) { "An encoded sample exceeded its buffer budget" }
                if (firstSampleUs < 0) firstSampleUs = extractor.sampleTime
                val flags = extractor.sampleFlags
                require(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio is unsupported" }
                val codecFlags = (if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or (if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                info.set(0, size, extractor.sampleTime - firstSampleUs, codecFlags)
                muxer.writeSampleData(output, buffer, info); wrote = true
                extractor.advance()
            }
            muxer.stop(); started = false
            muxer.release(); muxer = null
            if (!wrote) {
                // The chunk range holds no audio. It is reported as an empty slice so the
                // pipeline can complete it instead of retrying it for ever.
                target.delete()
                return PreparedAudioSlice(target, chunk.offsetMs + chunk.startUs / 1000, 0L)
            }
            val check = inspector.inspect(target)
            require(check.valid) { "The audio slice could not be validated" }
            return PreparedAudioSlice(target, chunk.offsetMs + firstSampleUs / 1000, check.durationMs)
        } catch (failure: Exception) { target.delete(); throw failure }
        finally { if (started) runCatching { muxer?.stop() }; runCatching { muxer?.release() }; extractor.release() }
    }
}
