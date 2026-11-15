package com.meetingatlas.dictation

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class Cleanup { None, Light, Medium }
internal enum class SpeechProvider(val label: String) { OPEN_ROUTER("OpenRouter"), OPEN_AI("OpenAI") }
internal enum class SpeechLanguage(val label: String, val hint: String?) {
    AUTO("Auto / mixed", null), ENGLISH("English", "en"), HINDI("Hindi", "hi"), HINGLISH("Hinglish", null)
}
internal enum class Microphone(val label: String) {
    SYSTEM("System default"), BUILTIN("Phone microphone"), BLUETOOTH("Bluetooth microphone")
}

@Serializable internal data class Snippet(val trigger: String, val text: String)
internal data class DictationSettings(
    val consent: Boolean = false,
    val provider: SpeechProvider = SpeechProvider.OPEN_ROUTER,
    val routerModel: String = "",
    val openAiModel: String = "",
    val cleanupModel: String = "",
    val language: SpeechLanguage = SpeechLanguage.AUTO,
    val cleanup: Cleanup = Cleanup.Light,
    val microphone: Microphone = Microphone.SYSTEM,
    val historyDays: Int = 0,
    val keepAudio: Boolean = false,
    val size: Int = 56,
    val opacity: Float = 1f,
    val idleShrink: Boolean = false,
    val onboardingDone: Boolean = false,
    val positionX: Float = 1f,
    val positionY: Float = 1f,
    val vocabulary: List<String> = emptyList(),
    val excluded: Set<String> = emptySet(),
    val snippets: List<Snippet> = emptyList(),
    val snippetsEnabled: Boolean = false,
) {
    val model: String get() = if (provider == SpeechProvider.OPEN_ROUTER) routerModel else openAiModel
}

private val Context.dictationStore by preferencesDataStore("atlas_dictation")

@Singleton
internal class DictationSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dictationStore
    private val updates = Mutex()
    private val stored = store.data.map { p ->
        DictationSettings(
            consent = p[booleanPreferencesKey("consent")] ?: false,
            provider = readEnum(p, "provider", SpeechProvider.OPEN_ROUTER),
            routerModel = p[stringPreferencesKey("routerModel")].orEmpty(),
            openAiModel = p[stringPreferencesKey("openAiModel")].orEmpty(),
            cleanupModel = p[stringPreferencesKey("cleanupModel")].orEmpty(),
            language = readEnum(p, "language", SpeechLanguage.AUTO),
            cleanup = readEnum(p, "cleanup", Cleanup.Light),
            microphone = readEnum(p, "microphone", Microphone.SYSTEM),
            historyDays = (p[intPreferencesKey("historyDays")] ?: 0).coerceIn(0, 90),
            keepAudio = p[booleanPreferencesKey("keepAudio")] ?: false,
            size = (p[intPreferencesKey("size")] ?: 56).coerceIn(48, 72),
            opacity = (p[floatPreferencesKey("opacity")] ?: 1f).coerceIn(.6f, 1f),
            idleShrink = p[booleanPreferencesKey("idleShrink")] ?: false,
            onboardingDone = p[booleanPreferencesKey("onboardingDone")] ?: false,
            positionX = (p[floatPreferencesKey("x")] ?: 1f).coerceIn(0f, 1f),
            positionY = (p[floatPreferencesKey("y")] ?: 1f).coerceIn(0f, 1f),
            vocabulary = readList(p, "vocabulary"),
            excluded = p[stringSetPreferencesKey("excluded")].orEmpty(),
            snippets = readList(p, "snippets"),
            snippetsEnabled = p[booleanPreferencesKey("snippetsEnabled")] ?: false,
        )
    }
    val data = stored.catch { if (it is IOException) emit(DictationSettings()) else throw it }
    // A failed read can disable capture, but cannot be interpreted as "erase history."
    suspend fun readDurable(): DictationSettings = stored.first()

    suspend fun consent(value: Boolean) { store.edit { it[booleanPreferencesKey("consent")] = value } }
    suspend fun finishOnboarding() { store.edit { it[booleanPreferencesKey("onboardingDone")] = true } }
    suspend fun position(x: Float, y: Float) { store.edit {
        it[floatPreferencesKey("x")] = x.coerceIn(0f, 1f)
        it[floatPreferencesKey("y")] = y.coerceIn(0f, 1f)
    } }
    suspend fun update(change: (DictationSettings) -> DictationSettings) = updates.withLock {
        // Only this screen writes these keys; consent and a concurrently dragged position
        // have separate transactions and are deliberately not rewritten here.
        val value = change(readDurable())
        store.edit {
            it[stringPreferencesKey("provider")] = value.provider.name
            it[stringPreferencesKey("routerModel")] = value.routerModel.trim().take(150)
            it[stringPreferencesKey("openAiModel")] = value.openAiModel.trim().take(150)
            it[stringPreferencesKey("cleanupModel")] = value.cleanupModel.trim().take(150)
            it[stringPreferencesKey("cleanup")] = value.cleanup.name
            it[stringPreferencesKey("language")] = value.language.name
            it[stringPreferencesKey("microphone")] = value.microphone.name
            it[intPreferencesKey("historyDays")] = value.historyDays.coerceIn(0, 90)
            it[booleanPreferencesKey("keepAudio")] = value.keepAudio && value.historyDays > 0
            it[intPreferencesKey("size")] = value.size.coerceIn(48, 72)
            it[floatPreferencesKey("opacity")] = value.opacity.coerceIn(.6f, 1f)
            it[booleanPreferencesKey("idleShrink")] = value.idleShrink
            it[stringPreferencesKey("vocabulary")] = Json.encodeToString(value.vocabulary.take(50).map { term -> term.trim().take(60) })
            it[stringPreferencesKey("snippets")] = Json.encodeToString(value.snippets.take(30))
            it[stringSetPreferencesKey("excluded")] = value.excluded.take(75).toSet()
            it[booleanPreferencesKey("snippetsEnabled")] = value.snippetsEnabled
        }
    }

    private inline fun <reified T : Enum<T>> readEnum(p: Preferences, name: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == p[stringPreferencesKey(name)] } ?: default

    private inline fun <reified T> readList(p: Preferences, name: String): List<T> =
        runCatching { Json.decodeFromString<List<T>>(p[stringPreferencesKey(name)] ?: "[]") }.getOrDefault(emptyList())
}
