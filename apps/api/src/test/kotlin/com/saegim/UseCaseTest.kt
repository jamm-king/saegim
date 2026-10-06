package com.saegim

import com.saegim.application.*
import com.saegim.application.port.*
import com.saegim.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID
import kotlin.test.*

// In-memory ports let use cases run without Spring, a database or an HTTP server.
private class MemoryMessages : MessageStore {
    val rows = linkedMapOf<Long, Message>()
    private var sequence = 0L
    override suspend fun save(message: Message): Message {
        val saved = message.copy(id = message.id ?: ++sequence)
        rows[saved.id!!] = saved
        return saved
    }
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun findByRequestId(requestId: String) = rows.values.find { it.requestId == requestId }
    override suspend fun findByInReplyTo(id: Long) = rows.values.find { it.inReplyTo == id }
    override suspend fun findAllById(ids: List<Long>) = ids.mapNotNull { rows[it] }
    override suspend fun forDay(start: LocalDateTime, end: LocalDateTime) = rows.values.filter {
        it.kind == "CHAT" && it.status == "COMPLETE" && it.createdAt >= start && it.createdAt < end
    }
    override suspend fun latestBefore(end: LocalDateTime) = rows.values.filter {
        it.kind == "CHAT" && it.status == "COMPLETE" && it.createdAt < end
    }.maxOfOrNull { it.createdAt }
    override suspend fun page(before: Long) = rows.values.filter { it.id!! < before }.sortedByDescending { it.id }.take(21)
    override suspend fun context(before: Long) = page(before).filter { it.kind == "CHAT" && it.status == "COMPLETE" }.take(20)
}
private class MemoryDays : ReviewDayStore {
    val rows = linkedMapOf<Long, ReviewDay>()
    override suspend fun save(day: ReviewDay): ReviewDay {
        val saved = day.copy(id = day.id ?: (rows.size + 1).toLong())
        rows[saved.id!!] = saved
        return saved
    }
    override suspend fun findByTargetDate(date: LocalDate) = rows.values.find { it.targetDate == date }
}
private class MemoryQuestions : ReviewQuestionStore {
    val rows = linkedMapOf<Long, ReviewQuestion>()
    override suspend fun save(question: ReviewQuestion): ReviewQuestion {
        val saved = question.copy(id = question.id ?: (rows.size + 1).toLong())
        rows[saved.id!!] = saved
        return saved
    }
    override suspend fun findById(id: Long) = rows[id]
    override suspend fun forDay(dayId: Long) = rows.values.filter { it.reviewDayId == dayId }.sortedBy { it.position }
    override suspend fun answered() = rows.values.filter { it.status == "ANSWERED" }
}
private object DirectTransaction : TransactionBoundary {
    override suspend fun <T : Any> execute(block: suspend () -> T): T = block()
}
private class FakeChatAi : ChatAi {
    override val settings = AiSettings("test", "fixture", true, mock = true)
    var calls = 0
    var fail = false
    override suspend fun reply(messages: List<Message>): String {
        calls++
        if (fail) throw AiUnavailable("테스트 실패")
        return "테스트 응답"
    }
}
private class FakeReviewAi : ReviewAi {
    val inputs = mutableListOf<List<Message>>()
    var fail = false
    var generate: (List<Message>) -> List<GeneratedQuestion> = { emptyList() }
    override suspend fun questions(source: List<Message>, reviewedIds: Set<Long>): List<GeneratedQuestion> {
        inputs += source.toList()
        if (fail) throw AiUnavailable("테스트 실패")
        return generate(source)
    }
    override suspend fun respond(question: ReviewQuestion, source: List<Message>, answer: String, hint: Boolean) = "테스트 피드백"
}

class UseCaseTest {
    @Test fun `chat logs a safe reason and message id without unexpected exception details`() = runTest {
        val messages = MemoryMessages()
        var unavailable = true
        val ai = object : ChatAi {
            override val settings = AiSettings("test", "fixture", true, mock = true)
            override suspend fun reply(messages: List<Message>): String {
                if (unavailable) throw AiUnavailable("출력 한도에 도달했습니다.", AiFailureReason.OUTPUT_LIMIT)
                throw IllegalStateException("PRIVATE_KEY PRIVATE_QUESTION")
            }
        }
        val logs = mutableListOf<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { logs += record.message }
            override fun flush() {}
            override fun close() {}
        }
        val logger = java.util.logging.Logger.getLogger(ChatService::class.java.name)
        logger.addHandler(handler)
        try {
            val service = ChatService(messages, ai, DirectTransaction)
            assertFailsWith<ApplicationFailure> { service.send(ChatRequest(UUID.randomUUID(), "PRIVATE_QUESTION")) }
            assertTrue(logs.last().contains("messageId=1 reason=OUTPUT_LIMIT"))
            assertTrue(logs.last().contains("출력 한도"))
            unavailable = false
            val failure = assertFailsWith<ApplicationFailure> { service.retry(1) }
            assertTrue(logs.last().contains("reason=INTERNAL_ERROR type=IllegalStateException"))
            assertFalse(logs.any { it.contains("PRIVATE") })
            assertFalse(failure.message!!.contains("PRIVATE"))
            assertFalse(messages.rows[1]!!.failureReason!!.contains("PRIVATE"))
        } finally { logger.removeHandler(handler) }
    }

    @Test fun `chat failure is saved and retried without duplicate turn`() = runTest {
        val messages = MemoryMessages()
        val ai = FakeChatAi()
        val service = ChatService(messages, ai, DirectTransaction)
        val request = ChatRequest(UUID.randomUUID(), "질문")
        ai.fail = true
        val error = assertFailsWith<ApplicationFailure> { service.send(request) }
        assertEquals(FailureKind.BAD_GATEWAY, error.kind)
        val failed = messages.rows.values.single()
        assertEquals("FAILED", failed.status)
        assertEquals("테스트 실패", failed.failureReason)
        assertEquals("테스트 실패", service.page(null).messages.single().failureReason)
        assertTrue(error.message!!.contains("테스트 실패"))
        ai.fail = false
        val retried = service.retry(failed.id!!)
        assertEquals(failed.id, retried.user.id)
        assertEquals("COMPLETE", retried.user.status)
        assertNull(retried.user.failureReason)
        assertEquals(retried, service.send(request))
        assertEquals(2, messages.rows.size)
        assertEquals(2, ai.calls)
        assertEquals(FailureKind.CONFLICT, assertFailsWith<ApplicationFailure> {
            service.send(request.copy(content = "다른 질문"))
        }.kind)
    }

    @Test fun `chat budget and paging remain use case rules`() = runTest {
        val messages = MemoryMessages()
        val ai = FakeChatAi()
        val service = ChatService(messages, ai, DirectTransaction)
        repeat(21) { messages.save(Message(role = "assistant", content = "x".repeat(3000))) }
        val first = service.page(null)
        assertEquals(20, first.messages.size)
        val older = service.page(first.nextCursor)
        assertEquals(1, older.messages.size)
        assertTrue(older.messages.single().id < first.messages.first().id)
        assertNull(older.nextCursor)
        assertFailsWith<ApplicationFailure> { service.send(ChatRequest(UUID.randomUUID(), "질문")) }
        assertEquals(0, ai.calls)
        assertEquals("FAILED", messages.rows.values.last().status)
    }

    @Test fun `review accumulates whole days only after zero and reuses saved result`() = runTest {
        val messages = MemoryMessages()
        val days = MemoryDays()
        val questions = MemoryQuestions()
        val ai = FakeReviewAi()
        val clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC)
        val service = ReviewService(days, questions, messages, ai, DirectTransaction, clock)
        val older = messages.save(Message(role = "assistant", content = "이전 설명", createdAt = LocalDateTime.parse("2026-09-30T01:00:00")))
        messages.save(Message(role = "assistant", content = "최근 설명 A", createdAt = LocalDateTime.parse("2026-10-01T01:00:00")))
        messages.save(Message(role = "assistant", content = "최근 설명 B", createdAt = LocalDateTime.parse("2026-10-01T02:00:00")))
        ai.generate = { source -> if (source.any { it.id == older.id }) listOf(GeneratedQuestion("질문", "기대 답", listOf(older.id!!))) else emptyList() }
        val result = service.prepare()
        assertEquals("READY", result.status)
        assertEquals(listOf(2, 3), ai.inputs.map { it.size })
        assertEquals("2026-09-30", result.sourceStartDate)
        assertEquals("2026-10-01", result.sourceEndDate)
        assertEquals(result, service.prepare())
        assertEquals(2, ai.inputs.size)
        assertEquals(listOf(older.id), questions.rows.values.single().sourceMessageIds)
    }

    @Test fun `review rejects invalid supplier evidence and retries latest day after failure`() = runTest {
        val messages = MemoryMessages()
        val days = MemoryDays()
        val questions = MemoryQuestions()
        val ai = FakeReviewAi()
        val service = ReviewService(days, questions, messages, ai, DirectTransaction,
            Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ZoneOffset.UTC))
        val latest = messages.save(Message(role = "assistant", content = "최근 설명", createdAt = LocalDateTime.parse("2026-10-01T01:00:00")))
        messages.save(Message(role = "assistant", content = "이전 설명", createdAt = LocalDateTime.parse("2026-09-30T01:00:00")))
        ai.generate = { listOf(GeneratedQuestion("질문", "기대 답", listOf(999L))) }
        assertEquals("FAILED", service.prepare().status)
        assertEquals(1, ai.inputs.size)
        assertTrue(questions.rows.isEmpty())
        ai.generate = { listOf(GeneratedQuestion("질문", "기대 답", listOf(latest.id!!))) }
        assertEquals("READY", service.prepare().status)
        assertEquals(listOf(listOf(latest.id), listOf(latest.id)), ai.inputs.map { source -> source.map { it.id } })
    }
}
