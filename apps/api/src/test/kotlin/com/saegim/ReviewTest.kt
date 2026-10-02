package com.saegim

import org.junit.jupiter.api.Test
import java.time.*
import kotlin.test.*

class ReviewTest {
    @Test fun `history window contains anchor and two previous calendar dates across month boundary`() {
        assertEquals(listOf("2026-10-01", "2026-09-30", "2026-09-29"), ReviewDates.candidates(LocalDate.parse("2026-10-01")).map { it.toString() })
        assertEquals(LocalDate.parse("2026-10-01"), ReviewDates.localDate(LocalDateTime.parse("2026-09-30T15:00:00")))
        assertEquals(LocalDate.parse("2026-09-30"), ReviewDates.localDate(LocalDateTime.parse("2026-09-30T14:59:59")))
    }
    @Test fun `Seoul midnight switches the target date`() {
        assertEquals(LocalDate.parse("2026-09-30"), ReviewDates.yesterday(Clock.fixed(Instant.parse("2026-10-01T14:59:59Z"), ZoneOffset.UTC)))
        assertEquals(LocalDate.parse("2026-10-01"), ReviewDates.yesterday(Clock.fixed(Instant.parse("2026-10-01T15:00:00Z"), ZoneOffset.UTC)))
    }
    @Test fun `previous day is a half open UTC interval`() {
        val (start, end) = ReviewDates.bounds(LocalDate.parse("2026-10-01"))
        assertEquals(LocalDateTime.parse("2026-09-30T15:00:00"), start)
        assertEquals(LocalDateTime.parse("2026-10-01T15:00:00"), end)
    }
    @Test fun `over budget fails instead of silently truncating`() {
        ReviewAi.checkBudget(listOf(Message(role = "assistant", content = "x".repeat(60000))))
        assertFailsWith<AiUnavailable> { ReviewAi.checkBudget(listOf(Message(role = "assistant", content = "x".repeat(60001)))) }
    }
    private val source = listOf(Message(id = 1, role = "user", content = "질문"), Message(id = 2, role = "assistant", content = "설명"))
    @Test fun `zero questions is a valid successful result`() { ReviewAi.validate(emptyList(), source) }
    @Test fun `invented evidence and user only evidence are rejected`() {
        for (ids in listOf(listOf(999L), listOf(1L), emptyList())) {
            assertFailsWith<AiUnavailable> { ReviewAi.validate(listOf(GeneratedQuestion("질문", "기대 답", ids)), source) }
        }
    }
    @Test fun `more than three or duplicate questions are rejected`() {
        val question = GeneratedQuestion("질문", "기대 답", listOf(2))
        assertFailsWith<AiUnavailable> { ReviewAi.validate(List(4) { question }, source) }
        assertFailsWith<AiUnavailable> { ReviewAi.validate(listOf(question, question), source) }
    }
}
