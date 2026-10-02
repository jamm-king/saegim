package com.saegim

import com.saegim.domain.*
import com.saegim.adapter.out.ai.*

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiClientTest {
    @Test
    fun `responses request and multiple output text blocks`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"resp_test","object":"response","usage":{"total_tokens":30},"status":"completed","output":[{"type":"reasoning","id":"rs_test"},{"id":"msg_test","role":"assistant","status":"completed","type":"message","content":[{"type":"output_text","text":"첫 문장","annotations":[]},{"type":"output_text","text":"둘째 문장","annotations":[]}]}]}"""))
            val ai = OpenAiClient("test-key", "test-model", server.url("/v1").toString())
            assertEquals("첫 문장\n둘째 문장", ai.reply(listOf(Message(role = "user", content = "질문"))))
            val sent = server.takeRequest()
            assertEquals("/v1/responses", sent.path)
            assertEquals("Bearer test-key", sent.getHeader("Authorization"))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("\"store\":false"))
            assertTrue(body.contains("질문"))
            assertFalse(body.contains("\"reasoning\""))
        }
    }

    @Test
    fun `luna disables reasoning for short text and structured review requests`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val ai = OpenAiClient("test-key", "gpt-6-luna", server.url("/v1").toString())
            for (format in listOf(null, mapOf<String, Any>("type" to "json_schema", "name" to "daily_review"))) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"응답"}]}]}"""))
                ai.generate("지시", listOf(AiInput("user", "질문")), 2500, format)
                val sent = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.takeRequest().body.readUtf8())
                assertEquals("gpt-6-luna", sent["model"].asText())
                assertEquals("none", sent["reasoning"]["effort"].asText())
                assertEquals(2500, sent["max_output_tokens"].asInt())
                assertEquals(format != null, sent.has("text"))
                assertFalse(sent.has("tools"))
            }
        }
    }

    @Test
    fun `incomplete and empty responses are failures`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val ai = OpenAiClient("test-key", "test-model", server.url("/v1").toString())
            for (body in listOf("""{"status":"incomplete","output":[]}""", """{"status":"completed","output":[]}""")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
                assertFailsWith<AiUnavailable> { ai.reply(listOf(Message(role = "user", content = "질문"))) }
            }
        }
    }

    @Test
    fun `provider error body is never exposed`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-provider-details"))
            val failure = assertFailsWith<AiUnavailable> { OpenAiClient("test-key", "test-model", server.url("/v1").toString()).reply(listOf(Message(role = "user", content = "질문"))) }
            assertFalse(failure.message!!.contains("sensitive"))
        }
    }

    @Test
    fun `no key means no mock answer`() = runTest {
        val ai = OpenAiClient("", "test-model", "http://localhost:1")
        assertFalse(ai.configured)
        assertFailsWith<AiUnavailable> { ai.reply(emptyList()) }
    }
}
