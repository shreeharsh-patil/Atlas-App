package com.meetingatlas.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Repackages compressed AAC samples into short M4A chunks without decoding them into PCM. */
@Singleton
class AudioChunker @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.cacheDir, "meeting-atlas-ai-audio")

    fun makeChunks(meetingId: String, inputFiles: List<File>): List<File> {
        val directory = File(root, meetingId).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val output = mutableListOf<File>()
        try {
            inputFiles.forEachIndexed { index, file -> split(file, directory, index, output) }
            if (output.isEmpty()) throw OpenRouterException("No readable audio was found in this recording.")
            return output
        } catch (error: Exception) {
            clear(meetingId)
            throw error
        }
    }

    fun clear(meetingId: String) {
        File(root, meetingId).listFiles()?.forEach { it.delete() }
        File(root, meetingId).delete()
    }

    private fun split(source: File, directory: File, sourceIndex: Int, output: MutableList<File>) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var currentOutput: File? = null
        var trackIndex = -1
        var chunkStartUs = -1L
        var sequence = 0
        try {
            extractor.setDataSource(source.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw OpenRouterException("Could not read an audio track from ${source.name}.")
            val format = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_SAMPLE_BUFFER)
            } else DEFAULT_SAMPLE_BUFFER
            val sampleBuffer = java.nio.ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()

            fun finishChunk() {
                val finished = muxer
                muxer = null
                finished?.let { try { it.stop() } finally { it.release() } }
                currentOutput?.takeIf { it.length() > MIN_AUDIO_CHUNK_BYTES }?.let(output::add)
                currentOutput = null
                trackIndex = -1
                chunkStartUs = -1L
            }

            while (true) {
                sampleBuffer.clear()
                val size = extractor.readSampleData(sampleBuffer, 0)
                if (size < 0) break
                val sampleTime = extractor.sampleTime
                if (muxer != null && sampleTime - chunkStartUs >= CHUNK_DURATION_US) finishChunk()
                if (muxer == null) {
                    sequence += 1
                    currentOutput = File(directory, "source-$sourceIndex-chunk-$sequence.m4a")
                    muxer = MediaMuxer(currentOutput!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    trackIndex = muxer!!.addTrack(format)
                    muxer!!.start()
                    chunkStartUs = sampleTime
                }
                val flags = extractor.sampleFlags
                check(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio cannot be prepared for transcription." }
                val codecFlags = (if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) or
                    (if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0)
                info.set(0, size, (sampleTime - chunkStartUs).coerceAtLeast(0), codecFlags)
                muxer!!.writeSampleData(trackIndex, sampleBuffer, info)
                extractor.advance()
            }
            finishChunk()
        } finally {
            muxer?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private companion object {
        const val CHUNK_DURATION_US = 120_000_000L
        const val DEFAULT_SAMPLE_BUFFER = 1024 * 1024
        const val MIN_AUDIO_CHUNK_BYTES = 512L
    }
}
