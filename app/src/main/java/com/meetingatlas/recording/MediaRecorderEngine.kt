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
        // The audio source is part of the fallback matrix: VOICE_COMMUNICATION is refused on
        // some builds and while a call is active, and MIC applies aggressive processing that
        // attenuates quiet speech in meetings.
        val sources = if (bluetooth) {
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        } else {
            listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        }
        var last: Exception? = null
        // The most likely configuration is tried first: a device that can roll over and a
        // supported sample rate should not be delayed by a full matrix of failures.
        for (rate in rates.map { it as Int? } + listOf(null)) {
            for (rollover in listOf(true, false)) {
                for (source in sources) {
                    @Suppress("DEPRECATION") val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                    try {
                        recorder.setAudioSource(source)
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        recorder.setAudioChannels(1)
                        rate?.let { recorder.setAudioSamplingRate(it) }
                        recorder.setAudioEncodingBitRate(quality.bitRate)
                        recorder.setOutputFile(file.absolutePath)
                        if (rollover) recorder.setMaxFileSize(quality.segmentBytes)
                        // A duration bound is independent of the encoder's bit-rate accuracy.
                        recorder.setMaxDuration(quality.segmentMillis)
                        recorder.prepare()
                        return recorder to EncoderConfiguration(rate, quality.bitRate, rollover)
                    } catch (failure: Exception) { last = failure; runCatching { recorder.release() } }
                }
            }
        }
        throw last ?: IllegalStateException("No compatible AAC recorder is available")
    }
}
