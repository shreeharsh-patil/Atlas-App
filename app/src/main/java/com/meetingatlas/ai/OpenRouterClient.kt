package com.meetingatlas.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class GeneratedMeetingNotes(
    val summary: String,
    val keyPoints: JSONArray,
    val decisions: JSONArray,
    val actionItems: JSONArray,
    val mindMap: JSONObject,
)

@Singleton
class OpenRouterClient @Inject constructor() {
    fun transcribe(audioFile: File, apiKey: String, model: String): String {
        require(audioFile.isFile && audioFile.length() > 0) { "An audio segment is missing or empty." }
        val boundary = "MeetingAtlas-${UUID.randomUUID()}"
        val connection = connection("/audio/transcriptions", apiKey).apply {
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val prefix = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n")
            append(model.trim())
            append("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            append("Content-Type: audio/mp4\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(prefix.size.toLong() + audioFile.length() + suffix.size)
        try {
            BufferedOutputStream(connection.outputStream, COPY_BUFFER).use { output ->
                output.write(prefix)
                BufferedInputStream(audioFile.inputStream(), COPY_BUFFER).use { input -> input.copyTo(output, COPY_BUFFER) }
                output.write(suffix)
            }
            val response = readJson(connection)
            return response.optString("text").trim().takeIf(String::isNotEmpty)
                ?: throw OpenRouterException("OpenRouter returned an empty transcript.")
        } finally {
            connection.disconnect()
        }
    }

    fun generateNotes(transcript: String, apiKey: String, model: String): GeneratedMeetingNotes {
        require(transcript.isNotBlank()) { "No speech was found in this recording." }
        val chunks = splitTranscript(transcript, MAX_TRANSCRIPT_CHARS)
        val sourceText = if (chunks.size == 1) chunks.single() else chunks.mapIndexed { index, chunk ->
            val condensed = complete(
                apiKey,
                model,
                "Condense this excerpt from a meeting into factual notes. Preserve names, commitments, decisions, and dates exactly when present. Do not infer missing owners or deadlines. Return concise plain text.",
                "Excerpt ${index + 1} of ${chunks.size}:\n\n$chunk",
                MAX_PARTIAL_TOKENS,
                structured = false,
            )
            "Excerpt ${index + 1}: $condensed"
        }.joinToString("\n\n")

        val result = JSONObject(complete(
            apiKey,
            model,
            "You create clear, evidence-bound meeting notes. Use only facts present in the source. Keep the summary concise. Put only explicitly agreed decisions in decisions. For action items, use empty strings where no owner or deadline was stated. Keep key points useful and specific. Build a compact mind map with one root, 3–7 branches, and up to 5 short topics per branch. Return the requested JSON only.",
            "Create notes from this meeting source:\n\n$sourceText",
            MAX_NOTES_TOKENS,
            structured = true,
        ))
        val summary = result.optString("summary").trim()
        val keyPoints = result.optJSONArray("key_points") ?: JSONArray()
        val decisions = result.optJSONArray("decisions") ?: JSONArray()
        val actionItems = result.optJSONArray("action_items") ?: JSONArray()
        val mindMap = result.optJSONObject("mind_map") ?: throw OpenRouterException("The model did not return a mind map. Your recording is still saved.")
        if (summary.isBlank() || mindMap.optString("label").isBlank()) {
            throw OpenRouterException("The model returned incomplete notes. Your recording is still saved.")
        }
        return GeneratedMeetingNotes(summary, keyPoints, decisions, actionItems, mindMap)
    }

    private fun complete(apiKey: String, model: String, system: String, user: String, maxTokens: Int, structured: Boolean): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", model.trim())
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
        if (structured) {
            body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "meeting_notes")
                .put("strict", true)
                .put("schema", notesSchema())))
            body.put("provider", JSONObject().put("require_parameters", true))
        }
        val connection = connection("/chat/completions", apiKey).apply {
            doOutput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = readJson(connection)
            val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.trim()?.takeIf(String::isNotEmpty)
                ?: throw OpenRouterException("OpenRouter returned an empty response.")
            return content
        } finally {
            connection.disconnect()
        }
    }

    private fun connection(path: String, apiKey: String): HttpURLConnection =
        (URL("https://openrouter.ai/api/v1$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val body = runCatching { JSONObject(text) }.getOrNull()
        if (status !in 200..299) {
            val detail = body?.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: "Request failed (HTTP $status). Check your key, model, connection, and OpenRouter balance."
            throw OpenRouterException(detail)
        }
        return body ?: throw OpenRouterException("OpenRouter returned an unreadable response.")
    }

    private fun notesSchema(): JSONObject {
        val stringArray = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
        val actionSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("task", JSONObject().put("type", "string"))
                .put("owner", JSONObject().put("type", "string"))
                .put("deadline", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("task").put("owner").put("deadline"))
            .put("additionalProperties", false)
        val branchSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("label", JSONObject().put("type", "string"))
                .put("topics", stringArray))
            .put("required", JSONArray().put("label").put("topics"))
            .put("additionalProperties", false)
        val mapSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("label", JSONObject().put("type", "string"))
                .put("branches", JSONObject().put("type", "array").put("items", branchSchema)))
            .put("required", JSONArray().put("label").put("branches"))
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("summary", JSONObject().put("type", "string"))
                .put("key_points", stringArray)
                .put("decisions", stringArray)
                .put("action_items", JSONObject().put("type", "array").put("items", actionSchema))
                .put("mind_map", mapSchema))
            .put("required", JSONArray().put("summary").put("key_points").put("decisions").put("action_items").put("mind_map"))
            .put("additionalProperties", false)
    }

    private fun splitTranscript(text: String, maximum: Int): List<String> {
        if (text.length <= maximum) return listOf(text)
        val chunks = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            var end = minOf(cursor + maximum, text.length)
            if (end < text.length) {
                val paragraph = text.lastIndexOf("\n\n", end).takeIf { it > cursor + maximum / 2 }
                val sentence = text.lastIndexOf('.', end).takeIf { it > cursor + maximum / 2 }
                end = paragraph ?: sentence?.plus(1) ?: end
            }
            chunks += text.substring(cursor, end).trim()
            cursor = end
        }
        return chunks.filter(String::isNotBlank)
    }

    companion object {
        const val TRANSCRIPTION_MODEL_DEFAULT = "openai/whisper-large-v3-turbo"
        private const val COPY_BUFFER = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 150_000
        private const val MAX_TRANSCRIPT_CHARS = 18_000
        private const val MAX_PARTIAL_TOKENS = 800
        private const val MAX_NOTES_TOKENS = 3_000
    }
}

class OpenRouterException(message: String) : Exception(message)
