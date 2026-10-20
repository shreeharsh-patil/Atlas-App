package com.meetingatlas.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingatlas.ai.MeetingProcessingRepository
import com.meetingatlas.ai.OpenRouterSettings
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.MeetingRepository
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.recording.RecordingPreferences
import com.meetingatlas.recording.RecordingOptions
import com.meetingatlas.recording.AudioQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProcessingUiState(
    val meetingId: String? = null,
    val running: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val audioDeleted: Boolean? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MeetingRepository,
    private val processingRepository: MeetingProcessingRepository,
    private val database: MeetingDatabase,
    private val recordingPreferences: RecordingPreferences,
    private val files: com.meetingatlas.recording.RecordingFileManager,
    private val notes: com.meetingatlas.notes.NotesRepository,
    private val scheduler: com.meetingatlas.notes.NotesScheduler,
) : ViewModel() {
    private val _meetingsReady = MutableStateFlow(false)
    val meetingsReady: StateFlow<Boolean> = _meetingsReady
    val meetings = repository.observeMeetings().onEach { _meetingsReady.value = true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _processing = MutableStateFlow(ProcessingUiState())
    val processing: StateFlow<ProcessingUiState> = _processing
    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage
    private val _settingsMessageIsError = MutableStateFlow(false)
    val settingsMessageIsError: StateFlow<Boolean> = _settingsMessageIsError
    private fun settingsResult(text: String, isError: Boolean) {
        _settingsMessage.value = text
        _settingsMessageIsError.value = isError
    }
    private val _hasOpenRouterKey = MutableStateFlow(processingRepository.settings().hasApiKey)
    val hasOpenRouterKey: StateFlow<Boolean> = _hasOpenRouterKey
    /**
     * `null` means "the stored preferences have not been read yet". Showing the built-in
     * defaults in the meantime would tell the user that source audio is deleted after notes
     * when their actual setting is the opposite.
     */
    val recordingOptions: StateFlow<RecordingOptions?> = recordingPreferences.options
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _localError = MutableStateFlow<String?>(null)
    val localError: StateFlow<String?> = _localError
    fun clearLocalError() { _localError.value = null }
    fun reportLocalError(message: String) { _localError.value = message }
    fun observeSegments(id: String) = database.audioSegmentDao().observeForMeeting(id)
    fun observeBookmarks(id: String) = database.recordingJournalDao().bookmarks(id)
    fun observeSession(id: String) = database.recordingJournalDao().observeSession(id)
    fun observeDiagnostics(id: String) = database.recordingJournalDao().events(id)
    suspend fun playbackPosition(id: String) = database.recordingJournalDao().playback(id)
    fun observeNotes(id: String) = notes.document(id)
    fun observeNotesJobs(id: String) = notes.jobs(id)
    fun observeTranscript(id: String, limit: Int) = notes.sources(id, limit)
    fun searchTranscript(id: String, query: String, limit: Int) = notes.searchSources(id, query, limit)
    suspend fun chapterTranscript(id: String) = notes.chapterTranscript(id)
    suspend fun chapterNotes(id: String) = notes.chapterNotes(id)
    suspend fun exportNotes(title: String, document: com.meetingatlas.data.NotesDocument, view: com.meetingatlas.notes.SummaryView) = notes.export(title, document, view)
    fun observeNoteHistory(id: String) = notes.history(id)
    fun observeSnapshots(id: String) = notes.snapshots(id)
    suspend fun noteEvidence(id: String) = notes.evidence(id)
    fun editNote(entry: com.meetingatlas.data.NoteEntryEntity, text: String, onSaved: () -> Unit = {}) = localChange { notes.edit(entry.id, entry.revision, text); onSaved() }
    fun collapseTopic(id: String, collapsed: Boolean) = localChange { notes.collapse(id, collapsed) }
    fun renameTopic(id: String, title: String) = localChange { notes.renameTopic(id, title) }
    fun highlightNote(id: String) = localChange { notes.toggleHighlight(id) }
    fun addNoteToMap(id: String) = localChange { notes.addToMap(id) }
    fun completeAction(id: String, completed: Boolean) = localChange { notes.setCompleted(id, completed) }
    fun queueNotes(id: String, template: com.meetingatlas.notes.MeetingTemplate, charging: Boolean = false, topicId: String? = null) = localChange { scheduler.enqueue(id, template, charging, topicId) }
    fun cancelNotes(jobId: String) = localChange { scheduler.cancel(jobId) }
    private fun localChange(block: suspend () -> Unit) {
        viewModelScope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { _localError.value = failure.message ?: "Changes could not be saved. Try again." } }
    }
    fun labelBookmark(id: String, label: String) = localChange { database.recordingJournalDao().labelBookmark(id, label.trim().take(160)) }
    fun deleteBookmark(id: String) = localChange { database.recordingJournalDao().deleteBookmark(id) }
    fun setQuality(quality: AudioQuality) = localChange { recordingPreferences.setQuality(quality) }
    fun setBluetooth(enabled: Boolean) = localChange { recordingPreferences.setBluetooth(enabled) }
    fun setDeleteAfterNotes(enabled: Boolean) = localChange { recordingPreferences.setDeleteAfterNotes(enabled) }
    fun deleteMeeting(id: String, onDeleted: () -> Unit) = localChange {
        val owner = com.meetingatlas.recording.AudioWorkCoordinator.startProcessing()
        check(owner != null) { "Audio is busy" }
        try {
            com.meetingatlas.playback.PlaybackInvalidation.invalidate(id)
            // The row is only removed once the audio files are gone, so a failed delete
            // leaves the meeting recoverable instead of orphaning its files.
            withContext(Dispatchers.IO) { check(files.deleteMeetingAudio(id, files.segments(id))) { "The recording files could not be removed. Nothing was deleted." } }
            database.meetingDao().delete(id)
        } finally { com.meetingatlas.recording.AudioWorkCoordinator.finishProcessing(owner) }
        // Navigate only after the process gate is released, so the next screen is not
        // blocked by a lock this screen is still holding.
        onDeleted()
    }

    fun observeAnalysis(id: String) = processingRepository.observeAnalysis(id)
    fun openRouterSettings(): OpenRouterSettings = processingRepository.settings()

    /** [onSaved] runs only after the encrypted key is durably stored, so a failed save never clears the input. */
    fun saveApiKey(key: String, onSaved: () -> Unit = {}) {
        if (key.isBlank()) { settingsResult("Enter an OpenRouter key before saving.", isError = true); return }
        viewModelScope.launch {
            _settingsMessage.value = null
            try {
                withContext(Dispatchers.IO) { processingRepository.saveApiKey(key) }
                _hasOpenRouterKey.value = true
                settingsResult("Key saved on this device.", isError = false)
                onSaved()
            } catch (cancelled: CancellationException) {
                // Never convert a cancellation into a user-visible error.
                throw cancelled
            } catch (failure: Exception) {
                settingsResult(failure.message ?: "Could not save the key.", isError = true)
            }
        }
    }

    fun deleteApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                processingRepository.deleteApiKey()
                _hasOpenRouterKey.value = false
                settingsResult("OpenRouter key removed.", isError = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                settingsResult(failure.message ?: "Could not remove the key.", isError = true)
            }
        }
    }

    fun saveModels(model: String, transcriptionModel: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { processingRepository.saveModels(model, transcriptionModel) }
                settingsResult("Model choices saved.", isError = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                settingsResult("Model choices could not be saved. Try again.", isError = true)
            }
        }
    }
    fun clearSettingsMessage() {
        _settingsMessage.value = null
        _settingsMessageIsError.value = false
    }

    fun rename(id: String, title: String) {
        val trimmed = title.trim().take(120)
        if (trimmed.isBlank()) return
        localChange { repository.rename(id, trimmed) }
    }

    fun generateNotes(id: String) {
        queueNotes(id, com.meetingatlas.notes.MeetingTemplate.GENERAL)
    }

    fun removeAudioAfterAnalysis(id: String) {
        viewModelScope.launch {
            _processing.value = ProcessingUiState(meetingId = id, running = true, message = "Removing source audio")
            try {
                val removed = withContext(Dispatchers.IO) { processingRepository.deleteAudioAfterAnalysis(id) }
                _processing.value = ProcessingUiState(meetingId = id, audioDeleted = removed,
                    error = if (removed) null else "Audio could not be removed. It remains on this device.")
                if (!removed) _localError.value = "Audio could not be removed. It remains on this device."
            } catch (failure: Exception) {
                _processing.value = ProcessingUiState(meetingId = id, error = failure.message ?: "Audio could not be removed.")
                _localError.value = failure.message ?: "Audio could not be removed."
            }
        }
    }

    fun clearProcessing() { if (!_processing.value.running) _processing.value = ProcessingUiState() }

    private val _creatingMeeting = MutableStateFlow(false)

    /** True while a draft meeting is being written, so the start button can be disabled. */
    val creatingMeeting: StateFlow<Boolean> = _creatingMeeting

    fun createMeeting(onCreated: (MeetingEntity) -> Unit) {
        // A rapid double tap must not create two draft meetings.
        if (_creatingMeeting.value) return
        _creatingMeeting.value = true
        // A dated default keeps the library readable; every meeting used to be called "Untitled meeting".
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())
        localChange {
            try { onCreated(repository.create("Meeting · $stamp")) } finally { _creatingMeeting.value = false }
        }
    }

    fun discardDraft(id: String) {
        localChange {
            // Remove an abandoned draft's files too; never touch audio while its session is still capturing.
            if (com.meetingatlas.recording.AudioWorkCoordinator.recording.value != id) {
                withContext(Dispatchers.IO) { files.deleteMeetingAudio(id, files.segments(id)) }
            }
            repository.discardDraft(id)
        }
    }
}
