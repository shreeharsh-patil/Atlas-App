package com.meetingatlas.ai

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the user's OpenRouter token encrypted with an Android Keystore-backed AES key. */
@Singleton
class OpenRouterSettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("meeting_atlas_ai", Context.MODE_PRIVATE)
    private val encrypted = context.getSharedPreferences("meeting_atlas_ai_secret", Context.MODE_PRIVATE)

    fun apiKey(): String? {
        val encoded = encrypted.getString(KEY_CIPHER_TEXT, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > IV_LENGTH) { "Saved OpenRouter key could not be read. Enter it again in Settings." }
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (failure: GeneralSecurityException) {
            // The Keystore entry was invalidated (a changed lock screen, a restore to a new
            // device, an OEM keystore fault). The unreadable key is removed so the user is
            // asked for it again instead of seeing a raw crypto exception for ever.
            runCatching { deleteApiKey() }
            throw IllegalStateException("Your saved OpenRouter key could not be read. Enter it again in Settings.", failure)
        }
    }

    fun saveApiKey(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        // commit() is used deliberately: the key must be durable before the UI reports success.
        @Suppress("UseKtx")
        check(encrypted.edit().putString(KEY_CIPHER_TEXT, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "Could not save the OpenRouter key on this device."
        }
    }

    /**
     * True only when a key is stored *and* can still be decrypted. A stored-but-unreadable
     * key must not be reported as "saved", or the app keeps offering actions that will fail.
     */
    fun hasApiKey(): Boolean = encrypted.contains(KEY_CIPHER_TEXT) && runCatching { apiKey() != null }.getOrDefault(false)

    fun deleteApiKey() {
        @Suppress("UseKtx")
        check(encrypted.edit().remove(KEY_CIPHER_TEXT).commit()) { "Could not remove the saved key." }
    }

    fun model(): String = preferences.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    fun saveModels(model: String, transcriptionModel: String) {
        @Suppress("UseKtx")
        check(preferences.edit().putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL }).putString(KEY_STT_MODEL, transcriptionModel.trim().ifBlank { DEFAULT_STT_MODEL }).commit()) { "Could not save model choices." }
    }
    fun transcriptionModel(): String = preferences.getString(KEY_STT_MODEL, DEFAULT_STT_MODEL).orEmpty().ifBlank { DEFAULT_STT_MODEL }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEY_STORE)
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build())
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "meeting_atlas_openrouter_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_CIPHER_TEXT = "ciphertext"
        const val KEY_MODEL = "model"
        const val KEY_STT_MODEL = "transcription_model"
        const val DEFAULT_MODEL = "openrouter/free"
        const val DEFAULT_STT_MODEL = "openai/whisper-large-v3-turbo"
    }
}
