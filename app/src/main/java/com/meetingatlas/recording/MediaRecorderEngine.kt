package com.meetingatlas.recording

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import java.io.File

data class EncoderConfiguration(val sampleRate: Int?, val bitRate: Int, val rollover: Boolean)

/** MediaRecorder still chooses the encoder. Capability probing narrows options; prepare verifies them. */
class MediaRecorderEngine(private val context: Context) {
    fun prepare(file: File, quality: AudioQuality, bluetooth: Boolean): Pair<MediaRecorder, EncoderConfiguration> {
        val rates = listOf(quality.sampleRate, 48000, 44100, 16000).distinct().filter { rate ->
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } &&
                    codec.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities?.let { it.isSampleRateSupported(rate) && it.bitrateRange.contains(quality.bitRate) } == true
            } }.getOrDefault(true)
        }
        var last: Exception? = null
        // A device can reject rollover. Retain a single file and report the limitation in the journal.
        for (rollover in listOf(true, false)) for (rate in rates.map { it as Int? } + listOf(null)) {
            @Suppress("DEPRECATION") val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            try {
                recorder.setAudioSource(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                rate?.let { recorder.setAudioSamplingRate(it) }
                recorder.setAudioEncodingBitRate(quality.bitRate)
                recorder.setOutputFile(file.absolutePath)
                if (rollover) recorder.setMaxFileSize(quality.segmentBytes)
                recorder.prepare()
                return recorder to EncoderConfiguration(rate, quality.bitRate, rollover)
            } catch (failure: Exception) { last = failure; runCatching { recorder.release() } }
        }
        throw last ?: IllegalStateException("No compatible AAC recorder is available")
    }
}
