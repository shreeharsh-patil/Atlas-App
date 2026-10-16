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
    private val _hasOpenRouterKey = MutableStateFlow(processingRepository.settings().hasApiKey)
    val hasOpenRouterKey: StateFlow<Boolean> = _hasOpenRouterKey
    val recordingOptions = recordingPreferences.options.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.meetingatlas.recording.RecordingOptions())
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
        check(com.meetingatlas.recording.AudioWorkCoordinator.startProcessing()) { "Audio is busy" }
        try {
            com.meetingatlas.playback.PlaybackInvalidation.invalidate(id)
            withContext(Dispatchers.IO) { check(files.deleteMeetingAudio(id, files.segments(id))) }
            database.meetingDao().delete(id)
            onDeleted()
        } finally { com.meetingatlas.recording.AudioWorkCoordinator.finishProcessing() }
    }

    fun observeAnalysis(id: String) = processingRepository.observeAnalysis(id)
    fun openRouterSettings(): OpenRouterSettings = processingRepository.settings()

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            _settingsMessage.value = null
            runCatching { withContext(Dispatchers.IO) { processingRepository.saveApiKey(key) } }
                .onSuccess { _hasOpenRouterKey.value = true; _settingsMessage.value = "Key saved on this device." }
                .onFailure { _settingsMessage.value = it.message ?: "Could not save the key." }
        }
    }

    fun deleteApiKey() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { processingRepository.deleteApiKey() }
                .onSuccess { _hasOpenRouterKey.value = false; _settingsMessage.value = "OpenRouter key removed." }
                .onFailure { _settingsMessage.value = it.message ?: "Could not remove the key." }
        }
    }

    fun saveModels(model: String, transcriptionModel: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { processingRepository.saveModels(model, transcriptionModel) } }
                .onSuccess { _settingsMessage.value = "Model choices saved." }
                .onFailure { _settingsMessage.value = "Model choices could not be saved. Try again." }
        }
    }
    fun clearSettingsMessage() { _settingsMessage.value = null }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        localChange { repository.rename(id, title.trim()) }
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

    fun createMeeting(onCreated: (MeetingEntity) -> Unit) {
        localChange { onCreated(repository.create("Untitled meeting")) }
    }

    fun discardDraft(id: String) {
        localChange { repository.discardDraft(id) }
    }
}
