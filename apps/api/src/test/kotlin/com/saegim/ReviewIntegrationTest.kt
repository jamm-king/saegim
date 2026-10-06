package com.saegim

import com.saegim.domain.*
import com.saegim.application.*
import com.saegim.application.port.TransactionBoundary
import com.saegim.adapter.out.persistence.*
import com.saegim.adapter.out.persistence.MessageEntity as Message

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
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReviewIntegrationConfig::class)
class ReviewIntegrationTest {
    @Autowired lateinit var review: ReviewService
    @Autowired lateinit var messages: MessageRepository
    @Autowired lateinit var days: ReviewDayRepository
    @Autowired lateinit var questions: ReviewQuestionRepository
    @Autowired lateinit var clock: TestReviewClock
    @Autowired lateinit var db: DatabaseClient
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var transactions: TransactionBoundary
    @Autowired lateinit var environment: Environment

    private fun http() = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:${environment.getRequiredProperty("local.server.port")}").build()

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
    private suspend fun sourceAt(date: String, content: String): Message = messages.save(Message(role = "assistant", content = content,
        createdAt = ReviewDates.bounds(LocalDate.parse(date)).first.plusHours(1))).awaitSingle()
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
        assertFailsWith<ApplicationFailure> { review.action(first.id, ReviewActionRequest(answerId, "다른 요청이 지연됩니다"), false) }
        server.takeRequest()
        assertEquals("FAILED", messages.findByRequestId(answerId.toString()).awaitSingle().status)
        assertEquals("ACTIVE", review.get().current!!.status)
        server.enqueue(response("다른 요청이 지연되는 점을 떠올렸습니다."))
        val failed = messages.findByRequestId(answerId.toString()).awaitSingle()
        assertTrue(failed.failureReason!!.contains("호출 제한"))
        assertFalse(failed.failureReason!!.contains("PRIVATE_PROVIDER"))
        val answered = review.retry(failed.id!!)
        server.takeRequest()
        assertEquals("ANSWERED", answered.review.current!!.status)
        assertEquals(failed.id, answered.messages.first().id)
        assertNull(answered.messages.first().failureReason)
        assertNull(messages.findById(failed.id!!).awaitSingle().failureReason)
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

    @Test fun `missed visits use latest chat day and preserve all of that day`() = runBlocking {
        clear()
        clock.time = Instant.parse("2026-10-05T03:00:00Z")
        val latest = sourceAt("2026-10-01", "LATEST_FULL_A")
        sourceAt("2026-10-01", "LATEST_FULL_B")
        sourceAt("2026-09-30", "OLDER_NOT_NEEDED")
        sourceAt("2026-10-05", "TODAY_EXCLUDED")
        server.enqueue(response(generated(latest.id!!, 1)))
        val prepared = review.prepare()
        val request = server.takeRequest().body.readUtf8()
        assertEquals("READY", prepared.status)
        assertEquals("2026-10-01", prepared.anchorDate)
        assertEquals("2026-10-01", prepared.sourceStartDate)
        assertEquals(prepared.sourceStartDate, prepared.sourceEndDate)
        assertTrue(request.contains("LATEST_FULL_A")); assertTrue(request.contains("LATEST_FULL_B"))
        assertFalse(request.contains("OLDER_NOT_NEEDED")); assertFalse(request.contains("TODAY_EXCLUDED"))
        val calls = server.requestCount
        assertEquals(prepared, review.prepare()); assertEquals(calls, server.requestCount)
        assertEquals(prepared, review.get())
    }

    @Test fun `zero questions expand cumulatively within three calendar days of latest history`() = runBlocking {
        clear()
        sourceAt("2026-09-25", "LATEST_DAY")
        sourceAt("2026-09-24", "SECOND_DAY")
        val oldest = sourceAt("2026-09-23", "THIRD_DAY")
        sourceAt("2026-09-22", "OUTSIDE_WINDOW")
        server.enqueue(response("{\"questions\":[]}")); server.enqueue(response("{\"questions\":[]}"))
        server.enqueue(response(generated(oldest.id!!, 1)))
        val result = review.prepare()
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        val third = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("LATEST_DAY")); assertFalse(first.contains("SECOND_DAY"))
        val firstInput = json.readTree(json.readTree(first)["input"][0]["content"].asText())
        assertTrue(firstInput.all { it["date"].asText() == "2026-09-25" })
        assertTrue(second.contains("LATEST_DAY")); assertTrue(second.contains("SECOND_DAY")); assertFalse(second.contains("THIRD_DAY"))
        assertTrue(third.contains("LATEST_DAY")); assertTrue(third.contains("SECOND_DAY")); assertTrue(third.contains("THIRD_DAY"))
        assertFalse(third.contains("OUTSIDE_WINDOW"))
        assertEquals("READY", result.status)
        assertEquals("2026-09-25", result.anchorDate)
        assertEquals("2026-09-23", result.sourceStartDate)
        assertEquals("2026-09-25", result.sourceEndDate)
    }

    @Test fun `empty days do not widen calendar window and failure does not fall back`() = runBlocking<Unit> {
        clear()
        sourceAt("2026-09-25", "ONLY_LATEST")
        sourceAt("2026-09-22", "TOO_OLD")
        server.enqueue(response("{\"questions\":[]}"))
        val empty = review.prepare(); val sent = server.takeRequest().body.readUtf8()
        assertEquals("EMPTY", empty.status); assertFalse(sent.contains("TOO_OLD"))
        val calls = server.requestCount
        assertEquals(empty, review.prepare()); assertEquals(calls, server.requestCount)
        clear()
        val latest = sourceAt("2026-09-25", "LATEST_FAIL")
        sourceAt("2026-09-24", "NO_FALLBACK_ON_FAILURE")
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals("FAILED", review.prepare().status)
        assertFalse(server.takeRequest().body.readUtf8().contains("NO_FALLBACK_ON_FAILURE"))
        server.enqueue(response(generated(latest.id!!, 1)))
        assertEquals("READY", review.prepare().status); server.takeRequest()
    }

    @Test fun `answered evidence is context only and unasked same-day material stays eligible`() = runBlocking<Unit> {
        clear()
        val answeredSource = sourceAt("2026-10-01", "ALREADY_ASKED")
        val unasked = sourceAt("2026-10-01", "NOT_YET_ASKED")
        server.enqueue(response(generated(answeredSource.id!!, 1)))
        assertEquals("READY", review.prepare().status); server.takeRequest()
        val question = review.start().review.current!!
        server.enqueue(response("피드백"))
        review.action(question.id, ReviewActionRequest(UUID.randomUUID(), "회상 답변"), false); server.takeRequest()
        review.next()
        clock.time = Instant.parse("2026-10-03T03:00:00Z")
        server.enqueue(response(generated(unasked.id!!, 1)))
        val result = review.prepare(); val request = server.takeRequest().body.readUtf8()
        assertEquals("READY", result.status)
        assertTrue(request.contains("ALREADY_ASKED")); assertTrue(request.contains("NOT_YET_ASKED"))
        val input = json.readTree(json.readTree(request)["input"][0]["content"].asText())
        assertTrue(input.first { it["id"].asLong() == answeredSource.id }["alreadyReviewed"].asBoolean())
        assertFalse(input.first { it["id"].asLong() == unasked.id }["alreadyReviewed"].asBoolean())
        // An erroneous model response referencing answered evidence must fail, not silently fall back.
        clock.time = Instant.parse("2026-10-04T03:00:00Z")
        server.enqueue(response(generated(answeredSource.id, 1)))
        assertEquals("FAILED", review.prepare().status); server.takeRequest()
    }

    @Test fun `HTTP chat contract validation conflicts and retry remain compatible`() = runBlocking<Unit> {
        clear()
        val client = http()
        client.get().uri("/api/settings").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.provider").isEqualTo("OpenAI").jsonPath("$.mock").isEqualTo(false)
        client.get().uri("/api/messages?before=0").exchange().expectStatus().isBadRequest
        client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("requestId" to UUID.randomUUID(), "content" to " ")).exchange().expectStatus().isBadRequest
        client.post().uri("/api/messages/999999/retry").exchange().expectStatus().isNotFound
        val requestId = UUID.randomUUID()
        server.enqueue(response("일반 대화 응답"))
        client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("requestId" to requestId, "content" to "질문")).exchange().expectStatus().isOk
            .expectBody().jsonPath("$.user.status").isEqualTo("COMPLETE")
            .jsonPath("$.assistant.content").isEqualTo("일반 대화 응답")
        server.takeRequest()
        val user = messages.findByRequestId(requestId.toString()).awaitSingle()
        val calls = server.requestCount
        client.post().uri("/api/messages/${user.id}/retry").exchange().expectStatus().isOk
        assertEquals(calls, server.requestCount)
        client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("requestId" to requestId, "content" to "다른 질문")).exchange().expectStatus().isEqualTo(409)
        val failedId = UUID.randomUUID()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"message","content":[{"type":"output_text","text":"PRIVATE_PARTIAL_ANSWER"}]}]}"""))
        client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("requestId" to failedId, "content" to "재시도 질문")).exchange().expectStatus().isEqualTo(502)
            .expectBody().jsonPath("$.detail").value<String> { assertTrue(it.contains("2400토큰")); assertFalse(it.contains("PRIVATE")) }
        server.takeRequest()
        val failed = messages.findByRequestId(failedId.toString()).awaitSingle()
        assertEquals("FAILED", failed.status)
        assertTrue(failed.failureReason!!.contains("2400토큰"))
        client.get().uri("/api/messages").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.messages[2].failureReason").isEqualTo(failed.failureReason)
        server.enqueue(response("재시도 응답"))
        client.post().uri("/api/messages/${failed.id}/retry").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.user.id").isEqualTo(failed.id!!.toInt())
        server.takeRequest()
        assertNull(messages.findById(failed.id!!).awaitSingle().failureReason)
        assertEquals(4L, messages.count().awaitSingle())
    }

    @Test fun `HTTP review hides expected answers and source evidence after mapping`() = runBlocking<Unit> {
        clear()
        val source = source()
        val client = http()
        server.enqueue(response(generated(source.id!!, 1)))
        val body = client.post().uri("/api/review/prepare").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("READY")
            .jsonPath("$.sourceStartDate").isEqualTo("2026-10-01").returnResult().responseBody!!
        server.takeRequest()
        val text = body.toString(Charsets.UTF_8)
        assertFalse(text.contains("expectedAnswer")); assertFalse(text.contains("sourceIds"))
        client.post().uri("/api/review/start").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.review.current.prompt").isEqualTo("회상 질문 1")
        client.post().uri("/api/review/questions/999999/answer").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("requestId" to UUID.randomUUID(), "content" to "답변")).exchange().expectStatus().isNotFound
    }

    @Test fun `transaction port rolls back real R2DBC writes`() = runBlocking<Unit> {
        clear()
        assertFailsWith<IllegalStateException> {
            transactions.execute {
                messages.save(Message(role = "assistant", content = "롤백 대상")).awaitSingle()
                throw IllegalStateException("force rollback")
            }
        }
        assertEquals(0L, messages.count().awaitSingle())
    }

    @Test fun `cumulative budget fails without truncation or later AI call`() = runBlocking {
        clear()
        sourceAt("2026-10-01", "x".repeat(30000))
        sourceAt("2026-09-30", "y".repeat(30001))
        server.enqueue(response("{\"questions\":[]}"))
        val before = server.requestCount
        val result = review.prepare(); server.takeRequest()
        assertEquals("FAILED", result.status)
        assertEquals(before + 1, server.requestCount)
        assertEquals("2026-09-30", result.sourceStartDate)
        assertEquals(0L, questions.count().awaitSingle())
    }
}
