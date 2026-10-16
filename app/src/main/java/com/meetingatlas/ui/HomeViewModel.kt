package com.meetingatlas.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetingatlas.ai.MeetingProcessingRepository
import com.meetingatlas.ai.OpenRouterSettings
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.MeetingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
) : ViewModel() {
    val meetings = repository.observeMeetings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _processing = MutableStateFlow(ProcessingUiState())
    val processing: StateFlow<ProcessingUiState> = _processing
    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage
    private val _hasOpenRouterKey = MutableStateFlow(processingRepository.settings().hasApiKey)
    val hasOpenRouterKey: StateFlow<Boolean> = _hasOpenRouterKey

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
        processingRepository.saveModel(model)
        processingRepository.saveTranscriptionModel(transcriptionModel)
        _settingsMessage.value = "Model choices saved."
    }
    fun clearSettingsMessage() { _settingsMessage.value = null }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.rename(id, title.trim()) }
    }

    fun generateNotes(id: String) {
        if (_processing.value.running) return
        viewModelScope.launch {
            _processing.value = ProcessingUiState(meetingId = id, running = true, message = "Preparing audio")
            try {
                val result = withContext(Dispatchers.IO) {
                    processingRepository.generate(id) { message ->
                        _processing.value = ProcessingUiState(meetingId = id, running = true, message = message)
                    }
                }
                _processing.value = ProcessingUiState(
                    meetingId = id,
                    audioDeleted = result.audioDeleted,
                    error = if (result.audioDeleted) null else "Notes are saved, but one or more audio files could not be removed.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _processing.value = ProcessingUiState(meetingId = id, error = failure.message ?: "Could not create meeting notes.")
            }
        }
    }

    fun removeAudioAfterAnalysis(id: String) {
        viewModelScope.launch {
            _processing.value = ProcessingUiState(meetingId = id, running = true, message = "Removing source audio")
            try {
                val removed = withContext(Dispatchers.IO) { processingRepository.deleteAudioAfterAnalysis(id) }
                _processing.value = ProcessingUiState(meetingId = id, audioDeleted = removed,
                    error = if (removed) null else "Audio could not be removed. It remains on this device.")
            } catch (failure: Exception) {
                _processing.value = ProcessingUiState(meetingId = id, error = failure.message ?: "Audio could not be removed.")
            }
        }
    }

    fun clearProcessing() { _processing.value = ProcessingUiState() }

    fun createMeeting(onCreated: (MeetingEntity) -> Unit) {
        viewModelScope.launch { onCreated(repository.create("Untitled meeting")) }
    }

    fun discardDraft(id: String) {
        viewModelScope.launch { repository.discardDraft(id) }
    }
}
