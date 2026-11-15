package com.meetingatlas.dictation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.meetingatlas.recording.RecordingInputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DictationAudio(val wav: ByteArray, val durationMs: Long, val autoStopped: Boolean)

internal class DictationAudioEngine @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // Checked before capture; runtime/OEM revocation is also handled in the service.
    suspend fun capture(
        microphone: Microphone,
        done: CompletableDeferred<Unit>,
        onStarted: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): DictationAudio = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw DictationFailure("Allow microphone access in Atlas.")
        }
        if (manager.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)) {
            throw DictationFailure("Microphone unavailable during a call.")
        }
        val bluetooth = microphone == Microphone.BLUETOOTH
        val routing = RecordingInputManager(context) {}
        var record: AudioRecord? = null
        var listener: AudioRouting.OnRoutingChangedListener? = null
        try {
            if (bluetooth && !routing.start(true)) {
                throw DictationFailure("Connect a Bluetooth microphone and allow Nearby devices, or select System default.")
            }
            val (recorder, rate) = open(microphone)
            record = recorder
            val changed = AtomicBoolean(false)
            val initialRoute = AtomicInteger(-1)
            listener = AudioRouting.OnRoutingChangedListener {
                val route = recorder.routedDevice?.id
                if (initialRoute.get() != -1 && route != initialRoute.get()) changed.set(true)
            }
            recorder.addOnRoutingChangedListener(listener, handler)
            withTimeoutOrNull(50_000) {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw DictationFailure("The microphone could not start.")
                val chunk = ShortArray(rate / 50)
                val buffer = PcmUtterance(rate)
                var frames = 0
                var emptyReads = 0
                var unverifiedReads = 0
                var nextRouteCheck = 0L
                while (!done.isCompleted && !buffer.full) {
                    currentCoroutineContext().ensureActive()
                    if (changed.get()) {
                        throw DictationFailure("Microphone changed while recording. Start a new dictation.")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextRouteCheck) {
                        nextRouteCheck = now + 300
                        if (initialRoute.get() != -1 && recorder.routedDevice?.id != initialRoute.get()) {
                            throw DictationFailure("Microphone changed while recording. Start a new dictation.")
                        }
                        val mode = manager.mode
                        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE ||
                            (!bluetooth && mode == AudioManager.MODE_IN_COMMUNICATION)) {
                            throw DictationFailure("A call interrupted the microphone.")
                        }
                        if (Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced == true) {
                            throw DictationFailure("Android muted the microphone. Check the privacy switch and other recording apps.")
                        }
                    }
                    val count = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
                    when {
                        count < 0 -> throw DictationFailure("Android interrupted microphone capture. Check for another app using the microphone.")
                        count == 0 -> {
                            if (++emptyReads > 240) throw DictationFailure("Android did not deliver microphone audio.")
                            delay(10)
                        }
                        else -> {
                            emptyReads = 0
                            if (initialRoute.get() == -1) {
                                val routed = recorder.routedDevice
                                val verified = when (microphone) {
                                    Microphone.BLUETOOTH -> isBluetooth(routed?.type)
                                    Microphone.BUILTIN -> routed?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                                    Microphone.SYSTEM -> true
                                }
                                if (!verified) {
                                    if (++unverifiedReads > 65) throw DictationFailure("Android could not route audio to the selected microphone.")
                                    continue
                                }
                                initialRoute.set(routed?.id ?: -1)
                                if (frames == 0) onStarted(RecordingInputManager.label(routed?.type))
                            }
                            val rms = buffer.add(chunk, count)
                            if (++frames % 3 == 0) onLevel((rms / 3600.0).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
                buffer.finish()
            } ?: throw DictationFailure("Microphone capture timed out. Try again.")
        } finally {
            record?.let { recorder ->
                listener?.let { runCatching { recorder.removeOnRoutingChangedListener(it) } }
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            routing.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(microphone: Microphone): Pair<AudioRecord, Int> {
        val preferred = if (microphone == Microphone.BUILTIN)
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: throw DictationFailure("The phone microphone is unavailable.")
        else null
        val sources = if (microphone == Microphone.BLUETOOTH) {
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        } else listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (rate in listOf(16_000, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            for (source in sources) {
                val candidate = try {
                    AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 2)).build()
                } catch (_: IllegalArgumentException) { continue }
                  catch (_: UnsupportedOperationException) { continue }
                if (candidate.state == AudioRecord.STATE_INITIALIZED &&
                    (preferred == null || candidate.setPreferredDevice(preferred))) return candidate to rate
                candidate.release()
            }
        }
        throw DictationFailure("No compatible microphone could be opened. Close other recorders and try again.")
    }

    private fun isBluetooth(type: Int?) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)
}

/** Energy gate trims only outside the utterance. Interior pauses stay intact. */
internal class PcmUtterance(private val rate: Int) {
    private val pcm = ShortArray(rate * 45)
    private var count = 0
    private var voiced = 0
    private var first = -1
    private var last = 0
    val full: Boolean get() = count == pcm.size

    fun add(frame: ShortArray, read: Int): Double {
        val n = minOf(read, pcm.size - count)
        if (n <= 0) return 0.0
        frame.copyInto(pcm, count, 0, n)
        var energy = 0.0
        repeat(n) { energy += frame[it].toDouble() * frame[it] }
        val rms = sqrt(energy / n)
        if (rms >= 130) {
            if (first == -1) first = count
            last = count + n
            voiced += n
        }
        count += n
        return rms
    }

    fun finish(): DictationAudio {
        if (first < 0 || voiced < rate / 9) throw DictationFailure("No speech heard. Speak clearly and try again.")
        val start = (first - rate / 5).coerceAtLeast(0)
        val end = (last + rate / 4).coerceAtMost(count)
        val size = (end - start) * 2
        val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        for (index in start until end) bytes.putShort(pcm[index])
        return DictationAudio(bytes.array(), (end - start) * 1000L / rate, full)
    }
}
