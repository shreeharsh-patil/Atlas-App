package com.meetingatlas.notes

import com.meetingatlas.ai.OpenRouterClient
import com.meetingatlas.ai.OpenRouterException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpenRouterTransportTest {
    @Test fun cancellingDuringASlowResponseBodyClosesTheCallPromptly() = runBlocking {
        val server = MockWebServer()
        val headers = java.util.concurrent.atomic.AtomicBoolean(false)
        val http = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headers.set(true) }
        }).build()
        try {
            server.enqueue(MockResponse().setBody("{\"choices\":[]}").setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val client = OpenRouterClient(server.url("/").toString(), http)
            val task = launch(start = CoroutineStart.UNDISPATCHED) { client.structuredJson("test-token", "openrouter/free", "system", "source", buildJsonObject { put("type", "object") }) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(2000) { while (!headers.get()) delay(10) }
            withTimeout(2000) { task.cancelAndJoin() }
            withTimeout(2000) { while (http.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue(task.isCancelled)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun temporaryProviderErrorsRetainStatusAndNeverEchoTheAuthToken() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota for test-secret-token"}}""")); server.start()
            val client = OpenRouterClient(server.url("/").toString(), http)
            try { client.structuredJson("test-secret-token", "openrouter/free", "s", "e", buildJsonObject { put("type", "object") }); fail("Expected rate limiting") }
            catch (failure: OpenRouterException) { assertEquals(429, failure.statusCode); assertFalse(failure.message!!.contains("test-secret-token")) }
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun modelLengthLimitCannotBeTreatedAsACompleteSummary() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"incomplete"}}]}""")); server.start()
            try { OpenRouterClient(server.url("/").toString(), http).structuredJson("test-token", "openrouter/free", "s", "e", buildJsonObject { put("type", "object") }); fail("Truncated summary was accepted") }
            catch (failure: OpenRouterException) { assertTrue(failure.message!!.contains("cut short")) }
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }
}
