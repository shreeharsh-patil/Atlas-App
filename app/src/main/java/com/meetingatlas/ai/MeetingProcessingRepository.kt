package com.meetingatlas.ai

import com.meetingatlas.data.AudioSegmentDao
import com.meetingatlas.data.MeetingAnalysisDao
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingDao
import com.meetingatlas.recording.RecordingFileManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

data class OpenRouterSettings(
    val hasApiKey: Boolean,
    val model: String,
    val transcriptionModel: String,
)

data class ProcessingResult(val audioDeleted: Boolean)

@Singleton
class MeetingProcessingRepository @Inject constructor(
    private val meetings: MeetingDao,
    private val audioSegments: AudioSegmentDao,
    private val analyses: MeetingAnalysisDao,
    private val settingsStore: OpenRouterSettingsStore,
    private val openRouter: OpenRouterClient,
    private val audioChunker: AudioChunker,
    private val fileManager: RecordingFileManager,
) {
    fun observeAnalysis(meetingId: String): Flow<MeetingAnalysisEntity?> = analyses.observe(meetingId)

    fun settings() = OpenRouterSettings(
        hasApiKey = settingsStore.hasApiKey(),
        model = settingsStore.model(),
        transcriptionModel = settingsStore.transcriptionModel(),
    )

    fun saveApiKey(key: String) = settingsStore.saveApiKey(key)
    fun deleteApiKey() = settingsStore.deleteApiKey()
    fun saveModel(model: String) = settingsStore.saveModel(model)
    fun saveTranscriptionModel(model: String) = settingsStore.saveTranscriptionModel(model)

    suspend fun generate(meetingId: String, progress: (String) -> Unit): ProcessingResult {
        val key = settingsStore.apiKey()?.takeIf(String::isNotBlank)
            ?: throw OpenRouterException("Add your OpenRouter API key in Settings to create meeting notes.")
        val meeting = meetings.findById(meetingId) ?: throw OpenRouterException("This meeting is no longer available.")
        val segments = audioSegments.findForMeeting(meetingId)
        val recordings = (segments.map { File(it.localPath) } + listOfNotNull(meeting.audioPath?.let(::File)))
            .distinctBy { it.absolutePath }
            .filter(File::isFile)
            .sortedBy { file -> segments.firstOrNull { it.localPath == file.absolutePath }?.sequence ?: Int.MAX_VALUE }
        if (recordings.isEmpty()) throw OpenRouterException("No saved recording is available. Your meeting notes have not changed.")

        val analysis = try {
            val chunks = audioChunker.makeChunks(meetingId, recordings)
            val transcript = buildString {
                chunks.forEachIndexed { index, file ->
                    progress("Transcribing ${index + 1} of ${chunks.size}")
                    val text = openRouter.transcribe(file, key, settingsStore.transcriptionModel())
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append(text)
                    }
                }
            }
            if (transcript.isBlank()) throw OpenRouterException("No speech was detected. Your recording is still saved.")
            progress("Writing summary and mind map")
            val generated = openRouter.generateNotes(transcript, key, settingsStore.model())
            MeetingAnalysisEntity(
                meetingId = meetingId,
                transcript = transcript,
                summary = generated.summary,
                keyPointsJson = generated.keyPoints.toString(),
                decisionsJson = generated.decisions.toString(),
                actionItemsJson = generated.actionItems.toString(),
                mindMapJson = generated.mindMap.toString(),
                generatedAt = System.currentTimeMillis(),
            ).also { analyses.save(it) }
        } finally {
            audioChunker.clear(meetingId)
        }

        progress("Removing the source recording")
        if (!fileManager.deleteMeetingAudio(meetingId, recordings)) {
            return ProcessingResult(audioDeleted = false)
        }
        meetings.clearAudioPath(meetingId, System.currentTimeMillis())
        audioSegments.deleteForMeeting(meetingId)
        analyses.markAudioDeleted(meetingId, System.currentTimeMillis())
        return ProcessingResult(audioDeleted = true)
    }

    suspend fun deleteAudioAfterAnalysis(meetingId: String): Boolean {
        if (analyses.find(meetingId) == null) return false
        val meeting = meetings.findById(meetingId) ?: return false
        val recordings = (audioSegments.findForMeeting(meetingId).map { File(it.localPath) } + listOfNotNull(meeting.audioPath?.let(::File)))
            .distinctBy { it.absolutePath }
        if (!fileManager.deleteMeetingAudio(meetingId, recordings)) return false
        meetings.clearAudioPath(meetingId, System.currentTimeMillis())
        audioSegments.deleteForMeeting(meetingId)
        analyses.markAudioDeleted(meetingId, System.currentTimeMillis())
        return true
    }
}
