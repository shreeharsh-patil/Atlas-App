package com.meetingatlas.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.room.Room
import com.meetingatlas.MainActivity
import com.meetingatlas.data.AudioSegmentDao
import com.meetingatlas.data.AudioSegmentEntity
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.data.MeetingDao
import com.meetingatlas.data.RecordingStatus
import com.meetingatlas.di.StorageModule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns microphone capture independently from any Activity or Compose lifecycle. */
class MeetingRecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private lateinit var files: RecordingFileManager
    private lateinit var meetingDao: MeetingDao
    private lateinit var segmentDao: AudioSegmentDao
    private var recorder: MediaRecorder? = null
    private var meetingId: String? = null
    private var activeFile: File? = null
    private val segmentFiles = mutableListOf<File>()
    private var nextFilePrepared = false
    private var sequence = 0
    private var accumulatedMs = 0L
    private var startedElapsed = 0L
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        files = RecordingFileManager(this)
        val db = Room.databaseBuilder(applicationContext, MeetingDatabase::class.java, "meeting-atlas.db")
            .addMigrations(StorageModule.MIGRATION_1_2)
            .build()
        meetingDao = db.meetingDao()
        segmentDao = db.audioSegmentDao()
        createNotificationChannel()
        serviceAlive = true
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_START) {
            val id = intent?.getStringExtra(EXTRA_MEETING_ID)
            if (id.isNullOrBlank()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Preparing recording", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            serviceScope.launch {
                commands.withLock {
                    if (recorder == null) startRecording(id)
                }
            }
        } else {
            serviceScope.launch {
                commands.withLock {
                    when (action) {
                        ACTION_PAUSE -> pauseRecording()
                        ACTION_RESUME -> resumeRecording()
                        ACTION_STOP -> stopRecording()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun startRecording(id: String) {
        meetingId = id
        accumulatedMs = 0
        sequence = 0
        segmentFiles.clear()
        paused = false
        RecordingProcessState.update(RecordingState.Preparing(id))
        try {
            meetingDao.setStatus(id, RecordingStatus.PREPARING, System.currentTimeMillis())
            if (files.availableBytes() < MIN_FREE_BYTES) error("Not enough free storage to start recording")
            val first = files.segment(id, ++sequence)
            prepareRecorder(first, firstSegment = true)
            recorder?.start()
            activeFile = first
            segmentFiles += first
            startedElapsed = android.os.SystemClock.elapsedRealtime()
            RecordingProcessState.update(RecordingState.Recording(id, startedElapsed))
            meetingDao.upsert(meetingDao.findById(id)!!.copy(startedAt = System.currentTimeMillis(), recordingStatus = RecordingStatus.RECORDING))
            updateNotification("Recording", true)
        } catch (failure: Exception) {
            releaseRecorder()
            RecordingProcessState.update(RecordingState.Failed(id, failure.message ?: "Could not start recording"))
            runCatching { meetingDao.finish(id, RecordingStatus.FAILED, 0, null, System.currentTimeMillis(), failure.message) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun prepareRecorder(file: File, firstSegment: Boolean) {
        file.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        recorder = instance
        try {
            configure(instance, file, usePreferredRate = true, firstSegment = firstSegment)
            instance.prepare()
        } catch (preferredFailure: Exception) {
            instance.reset()
            configure(instance, file, usePreferredRate = false, firstSegment = firstSegment)
            instance.prepare()
        }
        instance.setOnInfoListener { mediaRecorder, what, _ ->
            when (what) {
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> prepareNextSegment(mediaRecorder)
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                    activeFile?.let(::persistFinalizedSegment)
                    activeFile = segmentFiles.lastOrNull()
                    nextFilePrepared = false
                }
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                    activeFile?.let(::persistFinalizedSegment)
                    serviceScope.launch { commands.withLock { stopRecording("Storage segment could not be continued") } }
                }
            }
        }
        instance.setOnErrorListener { _, _, _ ->
            serviceScope.launch { commands.withLock { stopRecording("The microphone recorder stopped unexpectedly") } }
        }
    }

    private fun configure(instance: MediaRecorder, file: File, usePreferredRate: Boolean, firstSegment: Boolean) {
        instance.setAudioSource(MediaRecorder.AudioSource.MIC)
        instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        instance.setAudioChannels(1)
        if (usePreferredRate) instance.setAudioSamplingRate(24_000)
        instance.setAudioEncodingBitRate(48_000)
        instance.setOutputFile(file.absolutePath)
        if (firstSegment) instance.setMaxFileSize(SEGMENT_MAX_BYTES)
    }

    private fun prepareNextSegment(mediaRecorder: MediaRecorder) {
        if (nextFilePrepared) return
        val id = meetingId ?: return
        try {
            if (files.availableBytes() < MIN_FREE_BYTES) error("Storage is nearly full")
            val next = files.segment(id, sequence + 1)
            mediaRecorder.setNextOutputFile(next)
            sequence += 1
            segmentFiles += next
            nextFilePrepared = true
        } catch (failure: Exception) {
            serviceScope.launch { commands.withLock { stopRecording(failure.message ?: "Could not create next audio segment") } }
        }
    }

    private suspend fun pauseRecording() {
        val id = meetingId ?: return
        if (paused || recorder == null) return
        try {
            recorder?.pause()
            accumulatedMs = elapsedNow()
            paused = true
            RecordingProcessState.update(RecordingState.Paused(id, accumulatedMs))
            meetingDao.setStatus(id, RecordingStatus.PAUSED, System.currentTimeMillis())
            updateNotification("Paused", false)
        } catch (failure: Exception) {
            RecordingProcessState.update(RecordingState.Failed(id, failure.message ?: "Could not pause recording"))
            stopRecording(failure.message)
        }
    }

    private suspend fun resumeRecording() {
        val id = meetingId ?: return
        if (!paused || recorder == null) return
        try {
            recorder?.resume()
            paused = false
            startedElapsed = android.os.SystemClock.elapsedRealtime()
            RecordingProcessState.update(RecordingState.Recording(id, startedElapsed, accumulatedMs))
            meetingDao.setStatus(id, RecordingStatus.RECORDING, System.currentTimeMillis())
            updateNotification("Recording", true)
        } catch (failure: Exception) {
            stopRecording(failure.message ?: "Could not resume recording")
        }
    }

    private suspend fun stopRecording(error: String? = null) {
        val id = meetingId ?: return
        val current = recorder ?: return
        RecordingProcessState.update(RecordingState.Finalizing(id))
        var finalizationError = error
        try {
            current.setOnInfoListener(null)
            current.stop()
        } catch (_: RuntimeException) {
            // MediaRecorder can reject stop if no valid encoded frames were produced.
            if (finalizationError == null) finalizationError = "The final audio segment could not be finalized"
        } finally {
            releaseRecorder()
        }
        val validFiles = segmentFiles.filter { it.exists() && it.length() > MIN_VALID_FILE_BYTES }
        var segmentOffset = 0L
        val entries = validFiles.mapIndexedNotNull { index, file ->
            durationOf(file)?.let { duration ->
                AudioSegmentEntity(UUID.randomUUID().toString(), id, index + 1, file.absolutePath, segmentOffset, duration, file.length())
                    .also { segmentOffset += duration }
            }
        }
        val duration = if (paused) accumulatedMs else elapsedNow()
        val primaryPath = validFiles.firstOrNull()?.absolutePath
        val finalStatus = if (primaryPath != null && finalizationError == null) RecordingStatus.SAVED else if (primaryPath != null) RecordingStatus.INTERRUPTED else RecordingStatus.FAILED
        try {
            if (entries.isNotEmpty()) segmentDao.insertAll(entries)
            meetingDao.finish(id, finalStatus, duration, primaryPath, System.currentTimeMillis(), finalizationError)
            RecordingProcessState.update(
                if (finalStatus == RecordingStatus.SAVED) RecordingState.Saved(id)
                else if (finalStatus == RecordingStatus.INTERRUPTED) RecordingState.Interrupted(id, finalizationError.orEmpty())
                else RecordingState.Failed(id, finalizationError ?: "No valid audio was saved"),
            )
        } catch (failure: Exception) {
            finalizationError = failure.message ?: "Could not save recording metadata"
            RecordingProcessState.update(RecordingState.Failed(id, finalizationError!!))
            runCatching { meetingDao.finish(id, RecordingStatus.INTERRUPTED, duration, primaryPath, System.currentTimeMillis(), finalizationError) }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun persistFinalizedSegment(file: File) {
        if (!file.exists() || file.length() <= MIN_VALID_FILE_BYTES) return
        val id = meetingId ?: return
        val duration = durationOf(file) ?: return
        val offset = segmentFiles.takeWhile { it != file }.sumOf { durationOf(it) ?: 0L }
        serviceScope.launch {
            segmentDao.insertAll(listOf(AudioSegmentEntity(UUID.randomUUID().toString(), id, segmentFiles.indexOf(file) + 1, file.absolutePath, offset, duration, file.length())))
        }
    }

    private fun durationOf(file: File): Long? = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull().also { release() }
        }
    } catch (_: Exception) { null }

    private fun elapsedNow(): Long = accumulatedMs + if (paused) 0 else (android.os.SystemClock.elapsedRealtime() - startedElapsed)

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Meeting recordings", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Controls for an active Meeting Atlas recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(label: String, isRecording: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val controlAction = if (isRecording) ACTION_PAUSE else ACTION_RESUME
        val controlLabel = if (isRecording) "Pause" else "Resume"
        val control = PendingIntent.getService(this, 11, Intent(this, MeetingRecordingService::class.java).setAction(controlAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12, Intent(this, MeetingRecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Meeting Atlas")
            .setContentText(label)
            .setContentIntent(openIntent)
            .setWhen(System.currentTimeMillis() - if (isRecording) elapsedNow() else accumulatedMs)
            .setUsesChronometer(isRecording)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, controlLabel, control)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(label: String, isRecording: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(label, isRecording))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (recorder != null) serviceScope.launch { commands.withLock { stopRecording("Recording service ended") } }
        serviceAlive = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.meetingatlas.recording.START"
        const val ACTION_PAUSE = "com.meetingatlas.recording.PAUSE"
        const val ACTION_RESUME = "com.meetingatlas.recording.RESUME"
        const val ACTION_STOP = "com.meetingatlas.recording.STOP"
        const val EXTRA_MEETING_ID = "meeting_id"
        @Volatile var serviceAlive: Boolean = false
            private set
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 401
        private const val SEGMENT_MAX_BYTES = 8L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
        private const val MIN_VALID_FILE_BYTES = 1024L
    }
}
