package com.meetingatlas.dictation

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DictationTransportTest {
    private val settings = DictationSettings(routerModel = "provider/example", language = SpeechLanguage.HINDI)
    private val key = "test-only-token"

    @Test fun multipartSpeechReturnsMultilineUnicodeWithoutRewritingIt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"नमस्ते\nHello 🌿"}"""))
            val value = BatchTranscriptionEngine(transport(server)).transcribe("RIFFdemo".toByteArray(), settings, key)
            assertEquals("नमस्ते\nHello 🌿", value)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/audio/transcriptions", request.path)
            assertEquals("Bearer $key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            assertTrue(request.body.readUtf8().let { "filename=\"dictation.wav\"" in it && "RIFFdemo" in it &&
                "provider/example" in it && "name=\"language\"" in it && "hi\r\n" in it })
        }
    }

    @Test fun rejectsMalformedAndErrorResponsesWithoutShowingProviderSuppliedSecrets() = runBlocking {
        MockWebServer().use { server ->
            val engine = BatchTranscriptionEngine(transport(server))
            listOf(
                MockResponse().setBody("""{"text":17}"""),
                MockResponse().setBody("""{"text":"unexpected","error":{"message":"$key"}}"""),
                MockResponse().setResponseCode(429).setBody("""{"error":{"message":"$key"}}"""),
            ).forEach {
                server.enqueue(it)
                val error = try { engine.transcribe(byteArrayOf(1), settings, key); null }
                    catch (e: DictationFailure) { e }
                assertNotNull(error)
                assertTrue(error!!.retryable)
                assertFalse(error.message.orEmpty().contains(key))
            }
        }
    }

    @Test fun currentOpenAiSpeechUsesArrayLanguageHintsAndItsDefaultJsonResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"नमस्ते Atlas","languages":[{"code":"hi"}]}"""))
            val direct = settings.copy(provider = SpeechProvider.OPEN_AI, openAiModel = "gpt-transcribe", language = SpeechLanguage.HINGLISH)
            assertEquals("नमस्ते Atlas", BatchTranscriptionEngine(transport(server)).transcribe(byteArrayOf(1), direct, key))
            val body = server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
            assertEquals(2, body.split("name=\"languages[]\"").size - 1)
            assertTrue("hi\r\n" in body && "en\r\n" in body)
            assertFalse("name=\"language\"" in body || "name=\"response_format\"" in body)
        }
    }

    @Test fun cancellingARunningUploadStopsWaitingForTheResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"too late"}""").setBodyDelay(4, TimeUnit.SECONDS))
            val operation = async(Dispatchers.Default) {
                BatchTranscriptionEngine(transport(server)).transcribe(byteArrayOf(1), settings, key)
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1_500) { operation.cancelAndJoin() }
            assertTrue(operation.isCancelled)
        }
    }

    @Test fun cleanupRequiresAnExplicitModelAndFallsBackWhenItAddsANumber() = runBlocking {
        MockWebServer().use { server ->
            val enhancer = AiTextEnhancer(transport(server))
            val raw = "um send file 32"
            assertEquals(EnhancedText(raw), enhancer.enhance(raw, WritingStyle.EMAIL, settings, key))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody(response("Send file 33.")))
            val opted = settings.copy(cleanupModel = "my-chat-model", vocabulary = listOf("Atlas"))
            assertEquals(EnhancedText(raw, usedRaw = true), enhancer.enhance(raw, WritingStyle.EMAIL, opted, key))
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/chat/completions", request.path)
            assertTrue(request.body.readUtf8().let { "my-chat-model" in it && "professional punctuation" in it && "Atlas" in it })
            server.enqueue(MockResponse().setBody(response("Send file 32.")))
            assertEquals(EnhancedText("Send file 32."), enhancer.enhance(raw, WritingStyle.EMAIL, opted, key))
        }
    }

    @Test fun explicitSnippetsMatchOnlyTheEntireUtterance() = runBlocking {
        MockWebServer().use { server ->
            val enhancer = AiTextEnhancer(transport(server))
            val configured = settings.copy(snippets = listOf(Snippet("my signature", "Regards,\nदेव")))
            assertEquals("My signature.", enhancer.enhance("My signature.", WritingStyle.EMAIL, configured, key).text)
            val on = configured.copy(snippetsEnabled = true)
            assertEquals("Please add my signature.", enhancer.enhance("Please add my signature.", WritingStyle.EMAIL, on, key).text)
            assertEquals("Regards,\nदेव", enhancer.enhance("MY   signature.", WritingStyle.EMAIL, on, key).text)
            assertEquals(0, server.requestCount)
        }
    }

    private fun transport(server: MockWebServer) = DictationTransport(server.url("/api/v1/").toString(), OkHttpClient())
    private fun response(value: String) = """{"choices":[{"finish_reason":"stop","message":{"content":"$value"}}]}"""
}
