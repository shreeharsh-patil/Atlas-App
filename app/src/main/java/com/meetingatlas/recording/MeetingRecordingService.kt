package com.meetingatlas.recording

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.room.withTransaction
import com.meetingatlas.MainActivity
import com.meetingatlas.MeetingAtlasApplication
import com.meetingatlas.data.*
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ln
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recorder operations and callbacks are serialized on Main. Container inspection runs on IO. */
@AndroidEntryPoint
class MeetingRecordingService : Service() {
    @Inject lateinit var db: MeetingDatabase
    @Inject lateinit var files: RecordingFileManager
    @Inject lateinit var preferences: RecordingPreferences
    @Inject lateinit var inspector: SegmentInspector
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private val clock = RecordingClock(SystemClock::elapsedRealtime)
    private var recorder: MediaRecorder? = null
    private var meetingId: String? = null
    private var activeSequence = 1
    private var nextSequence: Int? = null
    private var sequence = 0
    private var offsetMs = 0L
    private var paused = false
    private var session: RecordingSessionEntity? = null
    private var quality = AudioQuality.BALANCED
    private var input: RecordingInputManager? = null
    private var storageJob: Job? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Meeting recordings", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        serviceAlive = true
        scope.launch {
            RecorderTelemetry.samples.subscriptionCount.collectLatest { subscribers ->
                if (subscribers > 0) while (isActive) {
                    val amplitude = if (!paused) runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) else 0
                    RecorderTelemetry.samples.tryEmit((ln(1.0 + amplitude) / ln(32768.0)).toFloat().coerceIn(0f, 1f))
                    delay(100)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val id = intent.getStringExtra(EXTRA_MEETING_ID)
            if (id.isNullOrBlank()) { if (meetingId == null) stopSelf(); return START_NOT_STICKY }
            if (meetingId != null) return START_NOT_STICKY
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing recording"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (_: Exception) {
                RecordingProcessState.update(RecordingState.Failed(id, "Android did not allow microphone access. Open the app and try again."))
                scope.launch { db.meetingDao().finish(id, RecordingStatus.FAILED, 0, null, System.currentTimeMillis(), "Microphone service start was not allowed"); stopSelf() }
                return START_NOT_STICKY
            }
            scope.launch { commands.withLock { if (meetingId == null) startRecording(id) } }
        } else if (meetingId != null) {
            scope.launch { commands.withLock {
                try {
                    when (action) {
                        ACTION_PAUSE -> pauseRecording()
                        ACTION_RESUME -> resumeRecording()
                        ACTION_STOP -> stopRecording()
                        ACTION_BOOKMARK -> bookmark(intent?.getStringExtra(EXTRA_BOOKMARK_LABEL).orEmpty())
                    }
                } catch (_: Exception) { stopRecording("A recording control could not be completed") }
            } }
        } else stopSelf()
        return START_NOT_STICKY
    }

    private suspend fun startRecording(id: String) {
        val meeting = db.meetingDao().findById(id)
        if (meeting == null || meeting.recordingStatus != RecordingStatus.PREPARING) {
            RecordingProcessState.update(RecordingState.Failed(id, "This meeting cannot be recorded again. Create a new recording."))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        if (!AudioWorkCoordinator.startRecording(id)) {
            val message = "Audio is busy processing or recovering. Try again when it finishes."
            RecordingProcessState.update(RecordingState.Failed(id, message))
            runCatching { db.meetingDao().finish(id, RecordingStatus.FAILED, 0, null, System.currentTimeMillis(), message) }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        meetingId = id
        sequence = 0; offsetMs = 0; nextSequence = null
        clock.start(); clock.pause()
        RecordingProcessState.update(RecordingState.Preparing(id))
        RecorderTelemetry.healthMutable.value = RecordingHealth()
        try {
            val options = preferences.options.first()
            quality = options.quality
            check(withContext(Dispatchers.IO) { files.availableBytes() } > RESERVE_BYTES * 2) { "Not enough free storage" }
            session = RecordingSessionEntity(id, quality.name, "PREPARING", bitRate = quality.bitRate, updatedAt = System.currentTimeMillis())
            journal("PREPARING")
            input = RecordingInputManager(this) { updateInput() }
            val bluetooth = input!!.start(options.bluetooth)
            if (options.bluetooth && !bluetooth) RecorderTelemetry.healthMutable.value = RecordingHealth(warning = "Bluetooth input is unavailable. Using system microphone routing.")
            val first = allocateSegment()
            val prepared = MediaRecorderEngine(this).prepare(first, quality, bluetooth)
            recorder = prepared.first
            session = session!!.copy(sampleRate = prepared.second.sampleRate ?: 0, bitRate = prepared.second.bitRate)
            journal("ENCODER_READY", "rate=${prepared.second.sampleRate ?: 0}; bitrate=${prepared.second.bitRate}; rollover=${prepared.second.rollover}")
            attachCallbacks(prepared.first)
            recorder!!.start()
            clock.start()
            activeSequence = sequence
            paused = false
            db.audioSegmentDao().markWriting(id, activeSequence)
            db.meetingDao().upsert(meeting.copy(startedAt = System.currentTimeMillis(), recordingStatus = RecordingStatus.RECORDING, updatedAt = System.currentTimeMillis()))
            journal("RECORDING")
            publishRecording()
            updateInput()
            if (!prepared.second.rollover) RecorderTelemetry.healthMutable.value = RecorderTelemetry.health.value.copy(warning = "This device uses a single audio file. Crash recovery may be limited.")
            updateNotification()
            storageJob = scope.launch { while (isActive) { delay(30_000); scope.launch { commands.withLock { checkStorage() } }.join() } }
        } catch (_: Exception) {
            stopRecording("Recording could not start. Check microphone permission, audio input and available storage.")
        }
    }

    private suspend fun allocateSegment(): File {
        val id = requireNotNull(meetingId)
        val number = ++sequence
        val file = withContext(Dispatchers.IO) { files.segment(id, number) }
        db.audioSegmentDao().insertAll(listOf(AudioSegmentEntity("$id:$number", id, number, file.absolutePath, offsetMs, 0, 0, "ALLOCATED")))
        journal("SEGMENT_ALLOCATED", "sequence=$number")
        return file
    }

    private fun attachCallbacks(instance: MediaRecorder) {
        instance.setOnInfoListener { _, what, extra -> scope.launch { commands.withLock {
            if (recorder !== instance) return@withLock
            try {
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> if (nextSequence == null) {
                        checkStorage()
                        if (recorder === instance) {
                            val next = allocateSegment()
                            instance.setNextOutputFile(next)
                            nextSequence = sequence
                        }
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                        val finished = activeSequence
                        activeSequence = nextSequence ?: sequence
                        nextSequence = null
                        db.audioSegmentDao().markWriting(requireNotNull(meetingId), activeSequence)
                        validateSegment(finished)
                        journal("SEGMENT_ROTATED", "sequence=$activeSequence")
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> stopRecording("The device could not continue into the next audio segment")
                    else -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) journal("ENCODER_INFO", "what=$what; extra=$extra")
                }
            } catch (_: Exception) { stopRecording("The next audio segment could not be prepared") }
        } } }
        instance.setOnErrorListener { _, what, extra -> scope.launch { commands.withLock {
            if (recorder === instance) {
                runCatching { journal("ENCODER_ERROR", "what=$what; extra=$extra") }
                stopRecording("The microphone recorder stopped unexpectedly")
            }
        } } }
        if (Build.VERSION.SDK_INT >= 28) {
            routingListener = AudioRouting.OnRoutingChangedListener { updateInput() }
            instance.addOnRoutingChangedListener(routingListener!!, Handler(Looper.getMainLooper()))
        }
        if (Build.VERSION.SDK_INT >= 29) {
            recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.isClientSilenced }) scope.launch { commands.withLock {
                        if (recorder === instance && !paused) {
                            RecorderTelemetry.healthMutable.value = RecorderTelemetry.health.value.copy(warning = "Android silenced the microphone. Recording is paused; resume when the microphone is available.")
                            runCatching { journal("INPUT_SILENCED"); pauseRecording() }.onFailure { stopRecording("The microphone became unavailable") }
                        }
                    } }
                }
            }
            instance.registerAudioRecordingCallback({ command -> Handler(Looper.getMainLooper()).post(command) }, recordingCallback!!)
        }
    }

    private fun updateInput() {
        val current = recorder ?: return
        val label = if (Build.VERSION.SDK_INT >= 28) RecordingInputManager.label(runCatching { current.routedDevice?.type }.getOrNull()) else "System microphone"
        if (label != RecorderTelemetry.health.value.input) {
            RecorderTelemetry.healthMutable.value = RecorderTelemetry.health.value.copy(input = label, warning = if (clock.elapsedMs > 2000) "Microphone input changed. Check the selected input." else RecorderTelemetry.health.value.warning)
            scope.launch { commands.withLock { if (recorder === current) runCatching { session = session?.copy(inputType = label); journal("INPUT_CHANGED", label) } } }
        }
    }

    private suspend fun pauseRecording() {
        val current = recorder ?: return
        if (paused) return
        journal("PAUSING")
        current.pause()
        clock.pause()
        paused = true
        db.meetingDao().setStatus(requireNotNull(meetingId), RecordingStatus.PAUSED, System.currentTimeMillis())
        journal("PAUSED")
        RecordingProcessState.update(RecordingState.Paused(requireNotNull(meetingId), clock.elapsedMs))
        updateNotification()
    }

    private suspend fun resumeRecording() {
        val current = recorder ?: return
        if (!paused) return
        journal("RESUMING")
        current.resume()
        clock.resume()
        paused = false
        db.meetingDao().setStatus(requireNotNull(meetingId), RecordingStatus.RECORDING, System.currentTimeMillis())
        journal("RECORDING")
        publishRecording()
        updateNotification()
    }

    private fun publishRecording() { RecordingProcessState.update(RecordingState.Recording(requireNotNull(meetingId), clock.startedAtElapsed, clock.elapsedBeforeStart)) }

    private suspend fun bookmark(label: String) {
        if (recorder == null || session?.state !in setOf("RECORDING", "PAUSED")) return
        val time = clock.elapsedMs
        db.recordingJournalDao().bookmark(BookmarkEntity(UUID.randomUUID().toString(), requireNotNull(meetingId), time, label.trim().take(160)))
        RecorderTelemetry.healthMutable.value = RecorderTelemetry.health.value.copy(bookmarkMessage = "Bookmark saved at ${formatRecordingTime(time)}")
    }

    private suspend fun checkStorage() {
        if (recorder == null) return
        val bytes = withContext(Dispatchers.IO) { files.availableBytes() }
        val minutes = ((bytes - RESERVE_BYTES).coerceAtLeast(0) / (quality.bitRate / 8) / 60)
        val old = RecorderTelemetry.health.value
        RecorderTelemetry.healthMutable.value = old.copy(storageMinutes = minutes, warning = if (minutes < 30) "Low storage: approximately $minutes minutes of recording space remain." else old.warning)
        if (bytes < RESERVE_BYTES) stopRecording("Storage is nearly full. Recoverable audio was saved.")
        else if (minutes < 30 && (old.storageMinutes == null || old.storageMinutes >= 30)) { journal("LOW_STORAGE", "minutes=$minutes"); updateNotification("Low recording storage") }
    }

    private suspend fun validateSegment(number: Int) {
        val id = requireNotNull(meetingId)
        val file = withContext(Dispatchers.IO) { files.segment(id, number) }
        val check = withContext(Dispatchers.IO) { inspector.inspect(file) }
        db.audioSegmentDao().insertAll(listOf(segmentEntry(id, number, file.absolutePath, check, offsetMs)))
        if (check.valid) offsetMs += check.durationMs
        journal(if (check.valid) "SEGMENT_VALIDATED" else "SEGMENT_INVALID", "sequence=$number; duration=${check.durationMs}; bytes=${check.bytes}")
    }

    private suspend fun stopRecording(reason: String? = null) {
        val id = meetingId ?: return
        clock.stop()
        storageJob?.cancel(); storageJob = null
        RecordingProcessState.update(RecordingState.Finalizing(id))
        var error = reason
        val current = recorder
        runCatching { journal("FINALIZING") }
        current?.setOnInfoListener(null); current?.setOnErrorListener(null)
        if (current != null) try { current.stop() } catch (_: Exception) { error = error ?: "The final segment could not be finalized" }
        releaseRecorder()
        try {
            offsetMs = 0
            for (number in 1..sequence) validateSegment(number)
            val entries = db.audioSegmentDao().findForMeeting(id)
            val valid = entries.filter { it.durationMs > 0 && it.validationStatus in setOf("VALIDATED", "RECOVERED") }
            if (valid.isEmpty()) error = error ?: "No playable audio was produced. Any incomplete files have been kept."
            if (entries.any { it.validationStatus == "INVALID" && it.fileSize > 0 }) error = error ?: "Some audio could not be finalized. Playable segments have been kept."
            val status = if (valid.isEmpty()) RecordingStatus.FAILED else if (error != null) RecordingStatus.INTERRUPTED else RecordingStatus.SAVED
            db.withTransaction {
                db.meetingDao().finish(id, status, offsetMs, valid.firstOrNull()?.localPath, System.currentTimeMillis(), error)
                journal(status.name)
            }
            RecordingProcessState.update(when (status) {
                RecordingStatus.SAVED -> RecordingState.Saved(id)
                RecordingStatus.INTERRUPTED -> RecordingState.Interrupted(id, requireNotNull(error))
                else -> RecordingState.Failed(id, error ?: "No audio was saved")
            })
        } catch (_: Exception) {
            RecordingProcessState.update(RecordingState.Failed(id, "Audio files were kept, but saving metadata failed. Reopen the app to recover them."))
        } finally {
            AudioWorkCoordinator.finishRecording(id)
            meetingId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun journal(state: String, detail: String = "") {
        val id = meetingId ?: return
        val states = setOf("PREPARING", "RECORDING", "PAUSING", "PAUSED", "RESUMING", "FINALIZING", "SAVED", "INTERRUPTED", "FAILED")
        session = session?.let { it.copy(state = if (state in states) state else it.state, elapsedMs = clock.elapsedMs, updatedAt = System.currentTimeMillis()) }
        db.withTransaction {
            session?.let { db.recordingJournalDao().saveSession(it) }
            db.recordingJournalDao().event(RecordingEventEntity(meetingId = id, type = state, detail = detail))
        }
    }

    private fun releaseRecorder() {
        recorder?.let { current ->
            if (Build.VERSION.SDK_INT >= 29) recordingCallback?.let { runCatching { current.unregisterAudioRecordingCallback(it) } }
            if (Build.VERSION.SDK_INT >= 28) routingListener?.let { runCatching { current.removeOnRoutingChangedListener(it) } }
            runCatching { current.release() }
        }
        recorder = null
        recordingCallback = null; routingListener = null
        runCatching { input?.close() }; input = null
    }

    private fun notification(label: String = if (paused) "Recording paused" else "Recording · saved on this device"): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, request: Int) = PendingIntent.getService(this, request, Intent(this, MeetingRecordingService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Meeting Atlas").setContentText(label)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis() - clock.elapsedMs).setUsesChronometer(recorder != null && !paused)
            .apply { if (recorder != null) {
                addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 11))
                addAction(0, "Bookmark", action(ACTION_BOOKMARK, 13))
                addAction(0, "Stop and save", action(ACTION_STOP, 12))
            } }.build()
    }
    private fun updateNotification(label: String? = null) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, if (label == null) notification() else notification(label)) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        val interrupted = meetingId
        if (recorder != null) { runCatching { recorder?.stop() }; releaseRecorder() }
        scope.cancel()
        serviceAlive = false
        interrupted?.let { AudioWorkCoordinator.finishRecording(it) }
        if (interrupted != null) (application as MeetingAtlasApplication).applicationScope.launch { RecordingRecoveryManager(applicationContext, db).recoverInterruptedSessions() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meetingatlas.recording.START"
        const val ACTION_PAUSE = "com.meetingatlas.recording.PAUSE"
        const val ACTION_RESUME = "com.meetingatlas.recording.RESUME"
        const val ACTION_STOP = "com.meetingatlas.recording.STOP"
        const val ACTION_BOOKMARK = "com.meetingatlas.recording.BOOKMARK"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_BOOKMARK_LABEL = "bookmark_label"
        @Volatile var serviceAlive = false; private set
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 401
        private const val RESERVE_BYTES = 32L * 1024 * 1024
    }
}
