package com.meetingatlas.recording

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

enum class AudioQuality(val label: String, val bitRate: Int, val sampleRate: Int) {
    STORAGE_SAVER("Storage saver", 24000, 16000), BALANCED("Balanced", 48000, 24000), HIGH("High quality", 96000, 48000);
    val megabytesPerHour: Int get() = bitRate * 3600 / 8 / 1000000
    val segmentBytes: Long get() = bitRate.toLong() * 20 * 60 / 8
    /** Independent duration bound for a single segment, in milliseconds. */
    val segmentMillis: Int get() = 20 * 60 * 1000
}

data class RecordingOptions(val quality: AudioQuality = AudioQuality.BALANCED, val bluetooth: Boolean = false, val deleteAfterNotes: Boolean = true)
private val Context.recordingDataStore by preferencesDataStore("recording_options")

@Singleton
class RecordingPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.recordingDataStore
    private val quality = stringPreferencesKey("quality")
    private val bluetooth = booleanPreferencesKey("bluetooth")
    private val deleteAfterNotes = booleanPreferencesKey("delete_after_notes")
    val options = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { prefs ->
        RecordingOptions(runCatching { AudioQuality.valueOf(prefs[quality].orEmpty()) }.getOrDefault(AudioQuality.BALANCED), prefs[bluetooth] ?: false, prefs[deleteAfterNotes] ?: true)
    }
    suspend fun setQuality(value: AudioQuality) { store.edit { it[quality] = value.name } }
    suspend fun setBluetooth(value: Boolean) { store.edit { it[bluetooth] = value } }
    suspend fun setDeleteAfterNotes(value: Boolean) { store.edit { it[deleteAfterNotes] = value } }
}
