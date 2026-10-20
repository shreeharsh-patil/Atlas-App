package com.meetingatlas.ai

import androidx.room.withTransaction
import com.meetingatlas.data.MeetingDatabase
import com.meetingatlas.recording.AudioWorkCoordinator
import com.meetingatlas.recording.RecordingFileManager
import javax.inject.Inject
import javax.inject.Singleton

data class OpenRouterSettings(val hasApiKey: Boolean, val model: String, val transcriptionModel: String)

/** Settings and legacy reads remain compatible; all new generation goes through NotesPipeline. */
@Singleton
class MeetingProcessingRepository @Inject constructor(private val database: MeetingDatabase, private val settingsStore: OpenRouterSettingsStore, private val files: RecordingFileManager) {
    fun observeAnalysis(id: String) = database.meetingAnalysisDao().observe(id)
    fun settings() = OpenRouterSettings(settingsStore.hasApiKey(), settingsStore.model(), settingsStore.transcriptionModel())
    fun saveApiKey(key: String) = settingsStore.saveApiKey(key)
    fun deleteApiKey() = settingsStore.deleteApiKey()
    fun saveModels(model: String, transcriptionModel: String) = settingsStore.saveModels(model, transcriptionModel)

    suspend fun deleteAudioAfterAnalysis(id: String): Boolean {
        val owner = AudioWorkCoordinator.startProcessing()
        check(owner != null) { "Finish the active audio operation before removing audio." }
        try {
            val state = database.notesDao().state(id)
            val legacy = database.meetingAnalysisDao().find(id)
            check(if (state != null) state.status in setOf("FINALIZED", "PARTIAL_AUDIO") else legacy != null) { "Finish processing before removing source audio." }
            com.meetingatlas.playback.PlaybackInvalidation.invalidate(id)
            if (!files.deleteMeetingAudio(id, files.segments(id))) return false
            database.withTransaction {
                database.meetingDao().clearAudioPath(id, System.currentTimeMillis())
                database.audioSegmentDao().deleteForMeeting(id)
                database.meetingAnalysisDao().markAudioDeleted(id, System.currentTimeMillis())
                // A targeted update avoids writing a stale copy of the whole notes_state row
                // over a concurrent pipeline update.
                state?.let { database.notesDao().markAudioDeleted(id, System.currentTimeMillis()) }
            }
            return true
        } finally { AudioWorkCoordinator.finishProcessing(owner) }
    }
}
