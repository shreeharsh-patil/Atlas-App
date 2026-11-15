package com.meetingatlas.dictation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.meetingatlas.R
import com.meetingatlas.recording.AudioWorkCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class DictationService : Service() {
    @Inject internal lateinit var coordinator: DictationCoordinator
    @Inject internal lateinit var engine: DictationAudioEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: CaptureSession? = null
    private var latestStart = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice dictation", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val id = intent?.getLongExtra(SESSION, -1) ?: -1
        if (intent?.action == CANCEL) {
            coordinator.cancel(id)
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val session = coordinator.session(id)
        if (current != null) {
            if (current?.id != id) coordinator.fail(id, "The previous microphone session is closing. Try again.")
            return START_NOT_STICKY
        }
        try {
            val cancel = PendingIntent.getService(this, id.toInt(), Intent(this, DictationService::class.java)
                .setAction(CANCEL).putExtra(SESSION, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Atlas voice dictation")
                .setContentText("Microphone active while you dictate").setOnlyAlertOnce(true)
                .setOngoing(true).setSilent(true).addAction(0, "Cancel", cancel).build()
            ServiceCompat.startForeground(this, NOTIFICATION, notification,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (_: RuntimeException) {
            coordinator.fail(id, "Android denied background microphone access. Open Atlas and try the microphone test.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (session == null) {
            closeService()
            return START_NOT_STICKY
        }
        val owner = "dictation:$id"
        if (!AudioWorkCoordinator.startDictation(owner)) {
            coordinator.fail(id, "A meeting or another session owns the microphone. Stop it before dictating.")
            closeService()
            return START_NOT_STICKY
        }
        current = session
        // LAZY assigns the capture job before IO starts, so an immediate Cancel can always reach it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val wav = engine.capture(session.settings.microphone, session.done,
                    onStarted = { route -> scope.launch { coordinator.started(id, route) } },
                    onLevel = { level -> scope.launch { coordinator.level(id, level) } },
                )
                coordinator.captured(id, wav)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DictationFailure) {
                coordinator.fail(id, failure.message.orEmpty())
            } catch (_: Exception) {
                coordinator.fail(id, "The microphone stopped unexpectedly. Check Android's microphone access and try again.")
            } finally {
                AudioWorkCoordinator.finishDictation(owner)
                current = null
                closeService()
            }
        }
        session.capture = job
        // Main.immediate enters the try/finally before control returns to this service callback.
        job.start()
        return START_NOT_STICKY
    }

    private fun closeService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStart)
    }

    override fun onDestroy() {
        current?.let { coordinator.fail(it.id, "Android stopped dictation. Start a new recording.") }
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val START = "com.meetingatlas.dictation.START"
        const val CANCEL = "com.meetingatlas.dictation.CANCEL"
        const val SESSION = "dictation_session"
        private const val CHANNEL = "atlas_dictation_capture"
        private const val NOTIFICATION = 2638
    }
}
