package com.saegim

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.UUID
import kotlin.test.*

class TestReviewClock : Clock() {
    var time: Instant = Instant.parse("2026-10-02T03:00:00Z")
    override fun instant() = time
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
}
@TestConfiguration
class ReviewIntegrationConfig {
    @Bean @Primary fun testReviewClock() = TestReviewClock()
}

// Only runs against an explicitly isolated MySQL schema supplied by the test runner.
@EnabledIfEnvironmentVariable(named = "RUN_REVIEW_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ReviewIntegrationConfig::class)
class ReviewIntegrationTest {
    @Autowired lateinit var review: ReviewService
    @Autowired lateinit var messages: MessageRepository
    @Autowired lateinit var days: ReviewDayRepository
    @Autowired lateinit var questions: ReviewQuestionRepository
    @Autowired lateinit var clock: TestReviewClock
    @Autowired lateinit var db: DatabaseClient
    @Autowired lateinit var json: ObjectMapper

    companion object {
        val server = MockWebServer().also { it.start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("saegim.openai.api-key") { "integration-test-key" }
            registry.add("saegim.openai.base-url") { server.url("/v1").toString() }
        }
    }
    private fun response(text: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(json.writeValueAsString(mapOf("status" to "completed", "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to text)))))))
    private suspend fun source(): Message = messages.save(Message(role = "assistant", content = "WebFlux 이벤트 루프에서 블로킹하면 다른 요청이 지연됩니다.", createdAt = LocalDateTime.parse("2026-10-01T02:00:00"))).awaitSingle()
    private fun generated(id: Long, count: Int = 2): String = json.writeValueAsString(mapOf("questions" to List(count) { index ->
        mapOf("question" to "회상 질문 ${index + 1}", "expectedAnswer" to "SERVER_ONLY_EXPECTED", "sourceIds" to listOf(id)) }))
    private suspend fun clear() {
        val database = db.sql("SELECT DATABASE() AS name").map { row, _ -> row.get("name", String::class.java)!! }.one().awaitSingle()
        check(database.startsWith("saegim_review_test")) { "Integration tests require an isolated saegim_review_test schema." }
        for (table in listOf("review_questions", "review_days", "messages")) db.sql("DELETE FROM $table").fetch().rowsUpdated().awaitSingle()
        clock.time = Instant.parse("2026-10-02T03:00:00Z")
    }

    @Test fun `real MySQL review generation progression restoration and exclusions`() = runBlocking {
        clear()
        val baseline = server.requestCount
        assertEquals("NO_CONVERSATION", review.prepare().status)
        assertEquals(baseline, server.requestCount)
        clear()
        val source = source()
        messages.save(Message(role = "assistant", content = "INCLUDED_AT_START", createdAt = LocalDateTime.parse("2026-09-30T15:00:00"))).awaitSingle()
        // Both outside boundaries, plus REVIEW and failed CHAT inside, must be excluded.
        for ((time, kind, status) in listOf(Triple("2026-09-30T14:59:59", "CHAT", "COMPLETE"), Triple("2026-10-01T15:00:00", "CHAT", "COMPLETE"), Triple("2026-10-01T02:00:00", "REVIEW", "COMPLETE"), Triple("2026-10-01T02:00:00", "CHAT", "FAILED"))) {
            messages.save(Message(role = "assistant", content = "EXCLUDED_RECORD", createdAt = LocalDateTime.parse(time), kind = kind, status = status)).awaitSingle()
        }
        server.enqueue(response(generated(source.id!!)))
        val ready = review.prepare()
        assertEquals("READY", ready.status); assertEquals(2, ready.total); assertNull(ready.current)
        val generatedRequest = server.takeRequest().body.readUtf8()
        assertFalse(generatedRequest.contains("EXCLUDED_RECORD"))
        assertTrue(generatedRequest.contains("INCLUDED_AT_START"))
        assertTrue(generatedRequest.contains("json_schema"))
        val calls = server.requestCount
        assertEquals(ready, review.prepare()); assertEquals(calls, server.requestCount)
        assertFalse(json.writeValueAsString(ready).contains("SERVER_ONLY"))
        assertEquals(2L, questions.count().awaitSingle())
        val started = review.start(); val first = started.review.current!!
        assertEquals("ACTIVE", first.status)
        assertEquals(started.review, review.start().review)
        assertFalse(json.writeValueAsString(started).contains("expectedAnswer"))
        assertEquals(1, started.messages.size)
        assertEquals(started.review, review.get()) // Server restoration from stored state.
        val hintId = UUID.randomUUID()
        server.enqueue(response("이벤트 루프에서 함께 처리되는 다른 요청을 떠올려 보세요."))
        val hint = review.action(first.id, ReviewActionRequest(hintId), true)
        server.takeRequest()
        assertEquals("ACTIVE", hint.review.current!!.status)
        val hintCalls = server.requestCount
        assertEquals(hint.messages, review.action(first.id, ReviewActionRequest(hintId), true).messages)
        assertEquals(hintCalls, server.requestCount)
        val answerId = UUID.randomUUID()
        server.enqueue(MockResponse().setResponseCode(429).setBody("PRIVATE_PROVIDER_ERROR"))
        assertFailsWith<org.springframework.web.server.ResponseStatusException> { review.action(first.id, ReviewActionRequest(answerId, "다른 요청이 지연됩니다"), false) }
        server.takeRequest()
        assertEquals("FAILED", messages.findByRequestId(answerId.toString()).awaitSingle().status)
        assertEquals("ACTIVE", review.get().current!!.status)
        server.enqueue(response("다른 요청이 지연되는 점을 떠올렸습니다."))
        val failed = messages.findByRequestId(answerId.toString()).awaitSingle()
        val answered = review.retry(failed.id!!)
        server.takeRequest()
        assertEquals("ANSWERED", answered.review.current!!.status)
        assertEquals(failed.id, answered.messages.first().id)
        assertEquals(answered.review, review.get())
        assertEquals(2, review.next().review.current!!.number)
        assertEquals("SKIPPED", review.skip().review.status)
        assertEquals("SKIPPED", review.prepare().status)

        clear(); source()
        server.enqueue(response("{\"questions\":[]}"))
        assertEquals("EMPTY", review.prepare().status); server.takeRequest()
        val emptyCalls = server.requestCount
        assertEquals("EMPTY", review.prepare().status); assertEquals(emptyCalls, server.requestCount)

        clear(); val source3 = source()
        server.enqueue(MockResponse().setResponseCode(500).setBody("PRIVATE_PROVIDER_ERROR"))
        val failure = review.prepare(); server.takeRequest()
        assertEquals("FAILED", failure.status)
        assertFalse(failure.error!!.contains("PRIVATE_PROVIDER"))
        server.enqueue(response(generated(source3.id!!, 1)))
        assertEquals("READY", review.prepare().status); server.takeRequest()
        assertEquals(1L, days.count().awaitSingle()); assertEquals(1L, questions.count().awaitSingle())
        val finalQuestion = review.start().review.current!!
        server.enqueue(response("복습 피드백"))
        review.action(finalQuestion.id, ReviewActionRequest(UUID.randomUUID(), "다른 요청이 지연됩니다"), false); server.takeRequest()
        assertEquals("COMPLETED", review.next().review.status)
        assertEquals("COMPLETED", review.prepare().status)
        clock.time = Instant.parse("2026-10-03T03:00:00Z")
        assertEquals("NO_CONVERSATION", review.prepare().status)
        assertEquals(2L, days.count().awaitSingle())

        clear(); source()
        server.enqueue(response(generated(999999)))
        assertEquals("FAILED", review.prepare().status); server.takeRequest()
        assertEquals(0L, questions.count().awaitSingle())
        clear()
        messages.save(Message(role = "assistant", content = "x".repeat(60001), createdAt = LocalDateTime.parse("2026-10-01T02:00:00"))).awaitSingle()
        val beforeBudget = server.requestCount
        assertEquals("FAILED", review.prepare().status)
        assertEquals(beforeBudget, server.requestCount)
    }
}
