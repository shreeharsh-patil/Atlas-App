package com.meetingatlas.dictation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.meetingatlas.ai.OpenRouterSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class DictationPhase { IDLE, STARTING, LISTENING, PROCESSING, INSERTING, SUCCESS, CANCELLED, ERROR, RECOVERING }
internal data class DictationStatus(
    val phase: DictationPhase = DictationPhase.IDLE,
    val message: String = "",
    val canRetry: Boolean = false,
) {
    val busy: Boolean get() = phase in setOf(DictationPhase.STARTING, DictationPhase.LISTENING,
        DictationPhase.PROCESSING, DictationPhase.INSERTING, DictationPhase.RECOVERING)
}
internal enum class InsertionResult { CONFIRMED, UNCONFIRMED, MANUAL }
internal data class CaptureSession(
    val id: Long, val target: Long?, val style: WritingStyle, val settings: DictationSettings,
    val done: CompletableDeferred<Unit> = CompletableDeferred(),
    var stoppedAt: Long = 0,
    var capture: Job? = null,
)
internal data class LatencySample(val audioFinalize: Long, val speech: Long, val cleanup: Long, val insert: Long, val total: Long)
internal interface DictationTarget {
    suspend fun deliver(target: Long, session: Long, text: String): InsertionResult
}

/** The service and all UI callers serialize ownership changes on the main thread. */
@MainThread
@Singleton
internal class DictationCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: DictationSettingsRepository,
    private val keyStore: OpenRouterSettingsStore,
    private val speech: SpeechToTextEngine,
    private val enhancer: AiTextEnhancer,
    private val history: DictationHistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storedSettings = settingsRepository.data
    val settings = storedSettings.stateIn(scope, SharingStarted.Eagerly, DictationSettings())
    private val fence = SessionFence()
    private var active: CaptureSession? = null
    private var pipeline: Job? = null
    private var reset: Job? = null
    private var startTimeout: Job? = null
    private var recoveryExpiry: Job? = null
    private var textExpiry: Job? = null
    private var retryAudio: DictationAudio? = null
    private val _status = MutableStateFlow(DictationStatus())
    val status = _status.asStateFlow()
    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()
    private val _result = MutableStateFlow<String?>(null)
    val result = _result.asStateFlow()
    private val _latency = MutableStateFlow<List<LatencySample>>(emptyList())
    val latency = _latency.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _lastIssue = MutableStateFlow<String?>(null)
    val lastIssue = _lastIssue.asStateFlow()
    private var targetBridge: DictationTarget? = null
    private data class HistoryWrite(val text: String?, val error: String?, val audio: DictationAudio)
    private val historyWrites = Channel<HistoryWrite>(capacity = 2)

    init {
        scope.launch {
            storedSettings.map { Triple(it.consent, it.historyDays, it.keepAudio) }.distinctUntilChanged().collect {
                if (!it.first) {
                    cancel("Dictation access is disabled.")
                    discardTransient()
                }
                try {
                    val preferences = settingsRepository.readDurable()
                    history.prune(preferences)
                    DictationRetentionWorker.schedule(context, preferences.historyDays > 0)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _lastIssue.value = "Could not read preferences or update local history." }
            }
        }
        scope.launch {
            for (write in historyWrites) {
                try {
                    val latest = settingsRepository.readDurable()
                    if (latest.consent) history.save(latest, write.text, write.error, write.audio)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _lastIssue.value = "Dictation could not be saved to local history."
                }
            }
        }
    }

    fun attach(target: DictationTarget) {
        targetBridge = target
        _connected.value = true
    }

    fun detach(target: DictationTarget) {
        if (targetBridge !== target) return
        targetBridge = null
        _connected.value = false
        active?.target?.let { targetLost(it) }
    }

    fun start(target: Long? = null, style: WritingStyle = WritingStyle.GENERAL): Long? {
        if (active != null) return null
        val options = ready() ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showError("Allow the microphone in Atlas.")
            return null
        }
        val id = fence.begin() ?: return null
        retryAudio = null
        recoveryExpiry?.cancel()
        val session = CaptureSession(id, target, style, options)
        active = session
        publish(DictationPhase.STARTING, "Opening microphone…")
        try {
            ContextCompat.startForegroundService(context, Intent(context, DictationService::class.java)
                .setAction(DictationService.START).putExtra(DictationService.SESSION, id))
            startTimeout = scope.launch {
                delay(6_000)
                if (owns(id) && (active?.capture == null || status.value.phase == DictationPhase.STARTING)) {
                    active?.capture?.cancel()
                    fail(id, "Android did not start the microphone. Open Atlas to check permissions.")
                }
            }
        } catch (_: RuntimeException) {
            fail(id, "Android blocked background microphone access. Open Atlas to run the microphone test.")
            return null
        }
        return id
    }

    fun session(id: Long) = active?.takeIf { fence.owns(id) && it.capture == null }
    fun owns(id: Long) = fence.owns(id)
    fun targetLost(target: Long) {
        val session = active ?: return
        if (session.target == target && status.value.phase in setOf(DictationPhase.STARTING, DictationPhase.LISTENING)) {
            cancel("Field changed. Recording discarded.")
        }
    }
    fun testHidden() {
        if (active?.target == null && status.value.phase in setOf(DictationPhase.STARTING, DictationPhase.LISTENING)) cancel()
    }
    fun started(id: Long, route: String) {
        if (owns(id) && status.value.phase == DictationPhase.STARTING) {
            startTimeout?.cancel()
            publish(DictationPhase.LISTENING, route)
        }
    }
    fun level(id: Long, level: Float) { if (owns(id)) _amplitude.value = level }

    fun done(id: Long? = active?.id) {
        val session = active ?: return
        if (session.id != id || status.value.phase !in setOf(DictationPhase.STARTING, DictationPhase.LISTENING)) return
        session.stoppedAt = SystemClock.elapsedRealtime()
        session.done.complete(Unit)
        publish(DictationPhase.PROCESSING, "Preparing audio…")
    }

    fun captured(id: Long, audio: DictationAudio) {
        val session = active?.takeIf { it.id == id && owns(id) } ?: return
        startTimeout?.cancel()
        if (session.stoppedAt == 0L) session.stoppedAt = SystemClock.elapsedRealtime()
        process(session, audio)
    }

    fun cancel(message: String = "Dictation cancelled.") {
        val session = active ?: return
        // Once commit has been dispatched, Android has no reliable rollback. The editor
        // will either acknowledge it or Atlas will present an unconfirmed result.
        if (status.value.phase == DictationPhase.INSERTING) return
        if (!fence.end(session.id)) return
        active = null
        session.capture?.cancel()
        pipeline?.cancel()
        startTimeout?.cancel()
        terminal(DictationPhase.CANCELLED, message)
    }

    fun cancel(id: Long) { if (owns(id)) cancel() }

    fun fail(id: Long, message: String, retry: Boolean = false) {
        if (!fence.end(id)) return
        active = null
        startTimeout?.cancel()
        terminal(DictationPhase.ERROR, message, retry)
    }

    fun showError(message: String) {
        if (active == null) terminal(DictationPhase.ERROR, message)
    }

    fun discardText() { textExpiry?.cancel(); _result.value = null }

    private fun discardTransient() {
        retryAudio = null
        recoveryExpiry?.cancel()
        discardText()
    }

    fun retry(entry: String? = null) {
        if (active != null) return
        val options = ready() ?: return
        val cached = if (entry == null) retryAudio ?: return else null
        val id = fence.begin() ?: return
        val session = CaptureSession(id, null, WritingStyle.GENERAL, options)
        active = session
        retryAudio = null
        recoveryExpiry?.cancel()
        publish(DictationPhase.RECOVERING, "Retrieving audio…")
        launchPipeline {
            val audio = cached ?: entry?.let { history.audio(it, settings.value) }
            if (audio == null) { fail(id, "Retained audio is unavailable."); return@launchPipeline }
            session.stoppedAt = SystemClock.elapsedRealtime()
            transcribe(session, audio)
        }
    }

    private fun ready(): DictationSettings? {
        val options = settings.value
        val error = when {
            !options.consent -> "Review and accept the dictation disclosure in Atlas."
            options.model.isBlank() -> "Choose a speech model in Dictation Settings."
            !keyStore.hasStoredKey(options.provider == SpeechProvider.OPEN_AI) -> "Save a key for the selected speech provider."
            else -> null
        }
        if (error != null) { showError(error); return null }
        return options
    }

    private fun process(session: CaptureSession, audio: DictationAudio) = launchPipeline { transcribe(session, audio) }

    private fun launchPipeline(task: suspend () -> Unit) {
        val id = active?.id ?: return
        pipeline = scope.launch {
            try { task() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(id, "Dictation could not finish. Open Atlas and try again.") }
        }
    }

    private suspend fun transcribe(session: CaptureSession, audio: DictationAudio) {
        val id = session.id
        if (!owns(id)) return
        val upload = SystemClock.elapsedRealtime()
        publish(DictationPhase.PROCESSING, if (audio.autoStopped) "Time limit reached. Transcribing…" else "Transcribing…")
        var saveError: String? = null
        val enhanced: EnhancedText
        val speechEnd: Long
        try {
            val key = withContext(Dispatchers.IO) {
                try { keyStore.key(session.settings.provider == SpeechProvider.OPEN_AI) }
                catch (_: Exception) { throw DictationFailure("The saved API key could not be read. Enter it again.", true) }
            } ?: throw DictationFailure("Save an API key for the selected provider and retry.", true)
            val raw = withContext(Dispatchers.Default) { speech.transcribe(audio.wav, session.settings, key) }
            speechEnd = SystemClock.elapsedRealtime()
            enhanced = withContext(Dispatchers.Default) { enhancer.enhance(raw, session.style, session.settings, key) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DictationFailure) {
            if (!owns(id)) return
            if (failure.retryable) {
                retryAudio = audio
                recoveryExpiry = scope.launch {
                    delay(120_000)
                    retryAudio = null
                    if (_status.value.phase == DictationPhase.ERROR) _status.value = _status.value.copy(canRetry = false)
                }
                saveError = failure.message
            }
            fail(id, failure.message.orEmpty(), failure.retryable)
            if (saveError != null) saveHistory(null, saveError, audio)
            return
        }
        val cleanEnd = SystemClock.elapsedRealtime()
        if (!owns(id)) return
        publish(DictationPhase.INSERTING, "Inserting…")
        // Keep a short-lived copy even when history is disabled. Never write the clipboard here.
        _result.value = enhanced.text
        textExpiry?.cancel()
        textExpiry = scope.launch { delay(180_000); _result.value = null }
        val outcome = session.target?.let { targetBridge?.deliver(it, id, enhanced.text) } ?: InsertionResult.MANUAL
        if (!fence.end(id)) return
        active = null
        val end = SystemClock.elapsedRealtime()
        if (session.target != null && outcome == InsertionResult.CONFIRMED) {
            _latency.value = (_latency.value + LatencySample(upload - session.stoppedAt, speechEnd - upload,
                cleanEnd - speechEnd, end - cleanEnd, end - session.stoppedAt)).takeLast(100)
        }
        val label = when (outcome) {
            InsertionResult.CONFIRMED -> "Inserted"
            InsertionResult.UNCONFIRMED -> "Check the editor. Atlas could not verify insertion."
            InsertionResult.MANUAL -> "Text ready. Copy it in Atlas."
        }
        terminal(DictationPhase.SUCCESS, if (enhanced.usedRaw) "$label (raw transcript)" else label)
        saveHistory(enhanced.text, null, audio)
    }

    private fun saveHistory(text: String?, error: String?, audio: DictationAudio) {
        val current = settings.value
        if (!current.consent || current.historyDays == 0 || (text == null && !current.keepAudio)) return
        // Keep capture responsive without queuing unlimited in-memory WAVs if storage stalls.
        if (!historyWrites.trySend(HistoryWrite(text, error, audio)).isSuccess) {
            _lastIssue.value = "Local history was busy. This dictation was not saved."
        }
    }

    private fun publish(phase: DictationPhase, text: String, retry: Boolean = false) {
        reset?.cancel()
        _status.value = DictationStatus(phase, text, retry)
        if (phase != DictationPhase.LISTENING) _amplitude.value = 0f
    }
    private fun terminal(phase: DictationPhase, text: String, retry: Boolean = false) {
        if (phase == DictationPhase.ERROR) _lastIssue.value = text
        publish(phase, text, retry)
        if (phase != DictationPhase.ERROR || !retry) reset = scope.launch {
            delay(when (phase) { DictationPhase.ERROR -> 7_000; DictationPhase.SUCCESS -> 5_000; else -> 2_000 })
            _status.value = DictationStatus()
        }
    }
}
