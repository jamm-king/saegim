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
            val request = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)
            assertEquals(2400, request["max_output_tokens"].asInt())
            assertTrue(request["instructions"].asText().contains("1,200토큰 이내를 목표"))
            assertTrue(request["instructions"].asText().contains("짧은 예제 하나"))
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
            for (body in listOf(
                """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"잘린 답변"}]}]}""",
                """{"status":"completed","output":[]}""",
            )) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
                assertFailsWith<AiUnavailable> { ai.reply(listOf(Message(role = "user", content = "질문"))) }
            }
        }
    }

    @Test
    fun `response failures report safe specific reasons`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val ai = OpenAiClient("test-key", "test-model", server.url("/v1").toString())
            val fixtures = listOf(
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""", AiFailureReason.OUTPUT_LIMIT, "2400토큰"),
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}""", AiFailureReason.CONTENT_FILTER, "콘텐츠 제한"),
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"PRIVATE_PROVIDER_DETAIL"},"output":[]}""", AiFailureReason.INCOMPLETE, "완성된 응답"),
                Triple("""{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"PRIVATE_PROVIDER_DETAIL"}]}]}""", AiFailureReason.REFUSAL, "거절"),
                Triple("""{"status":"completed","output":[]}""", AiFailureReason.EMPTY_RESPONSE, "답변 내용"),
                Triple("not-json PRIVATE_PROVIDER_DETAIL", AiFailureReason.INVALID_RESPONSE, "읽지 못했습니다"),
            )
            for ((body, reason, expected) in fixtures) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
                val failure = assertFailsWith<AiUnavailable> { ai.reply(listOf(Message(role = "user", content = "PRIVATE_QUESTION"))) }
                assertEquals(reason, failure.reason)
                assertTrue(failure.message!!.contains(expected))
                assertFalse(failure.message!!.contains("PRIVATE"))
            }
        }
    }

    @Test
    fun `connection failure does not expose request secrets`() = runTest {
        val server = MockWebServer()
        server.start()
        val url = server.url("/v1").toString()
        server.close()
        val failure = assertFailsWith<AiUnavailable> { OpenAiClient("PRIVATE_KEY", "test-model", url).reply(listOf(Message(role = "user", content = "PRIVATE_QUESTION"))) }
        assertEquals(AiFailureReason.CONNECTION, failure.reason)
        assertFalse(failure.message!!.contains("PRIVATE"))
    }

    @Test
    fun `provider error body is never exposed`() = runTest {
        MockWebServer().use { server ->
            server.start()
            for ((status, reason) in listOf(401 to AiFailureReason.AUTHENTICATION, 403 to AiFailureReason.AUTHENTICATION, 404 to AiFailureReason.MODEL, 429 to AiFailureReason.RATE_LIMIT, 500 to AiFailureReason.HTTP_ERROR)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive-provider-details"))
                val failure = assertFailsWith<AiUnavailable> { OpenAiClient("test-key", "test-model", server.url("/v1").toString()).reply(listOf(Message(role = "user", content = "질문"))) }
                assertEquals(reason, failure.reason)
                assertFalse(failure.message!!.contains("sensitive"))
            }
        }
    }

    @Test
    fun `no key means no mock answer`() = runTest {
        val ai = OpenAiClient("", "test-model", "http://localhost:1")
        assertFalse(ai.configured)
        assertFailsWith<AiUnavailable> { ai.reply(emptyList()) }
    }
}
