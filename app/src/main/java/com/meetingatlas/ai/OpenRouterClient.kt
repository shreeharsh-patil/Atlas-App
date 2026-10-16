package com.meetingatlas.ai

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class OpenRouterClient private constructor(private val http: OkHttpClient, private val baseUrl: String) {
    @Inject constructor() : this(defaultHttp(), "https://openrouter.ai/api/v1/")
    internal constructor(baseUrl: String, http: OkHttpClient) : this(http, baseUrl)

    suspend fun structured(apiKey: String, model: String, system: String, user: String, schema: JSONObject): String =
        structuredJson(apiKey, model, system, user, Json.parseToJsonElement(schema.toString()).jsonObject)

    internal suspend fun structuredJson(apiKey: String, model: String, system: String, user: String, schema: JsonObject): String {
        val body = buildJsonObject {
            put("model", model); put("temperature", 0.2); put("max_tokens", 7000)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "meeting_knowledge"); put("strict", true); put("schema", schema) }
            }
            putJsonObject("provider") { put("require_parameters", true) }
        }
        val response = request("chat/completions", apiKey, body.toString().toRequestBody("application/json".toMediaType()))
        val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw OpenRouterException("OpenRouter returned no notes. Saved progress is safe.")
        if (choice["finish_reason"]?.jsonPrimitive?.content == "length") throw OpenRouterException("The model output was cut short. Saved progress is safe; retry this window.")
        return choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OpenRouterException("OpenRouter returned an empty response. Saved progress is safe.")
    }

    suspend fun transcribeTimed(audioFile: File, apiKey: String, model: String): String {
        require(audioFile.isFile && audioFile.length() in 1..25_000_000L) { "The audio chunk is missing or too large" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("file", "audio.m4a", audioFile.asRequestBody("audio/mp4".toMediaType())).build()
        return request("audio/transcriptions", apiKey, body).toString()
    }

    private suspend fun request(path: String, apiKey: String, body: RequestBody): JsonObject {
        val token = apiKey.trim()
        require(token.isNotBlank() && token.all { it in '!'..'~' }) { "The saved API key has invalid characters. Enter it again in Settings." }
        val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").header("Accept", "application/json").post(body).build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            // Remains registered while headers AND the bounded response body are read.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val text = response.body?.charStream()?.use { reader ->
                                val buffer = CharArray(8192)
                                buildString { while (true) { val count = reader.read(buffer); if (count < 0) break; check(length + count <= 2 * 1024 * 1024) { "Provider response exceeded its safe size limit" }; append(buffer, 0, count) } }
                            }.orEmpty()
                            val parsed = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                            if (!response.isSuccessful) {
                                val detail = ((parsed?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.content
                                    ?: "Request failed (HTTP ${response.code}). Check the key, model and OpenRouter balance."
                                throw OpenRouterException(detail.replace(token, "[redacted]"), response.code)
                            }
                            val result = parsed ?: throw OpenRouterException("The provider returned unreadable JSON. Saved progress is safe.")
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    }
                }
            })
        }
    }
    companion object {
        private fun defaultHttp() = OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    }
}

class OpenRouterException(message: String, val statusCode: Int? = null) : Exception(message)
