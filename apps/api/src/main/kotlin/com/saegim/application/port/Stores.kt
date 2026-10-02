package com.saegim.application.port

import com.saegim.domain.*
import java.time.LocalDate
import java.time.LocalDateTime

interface MessageStore {
    suspend fun save(message: Message): Message
    suspend fun findById(id: Long): Message?
    suspend fun findByRequestId(requestId: String): Message?
    suspend fun findByInReplyTo(id: Long): Message?
    suspend fun findAllById(ids: List<Long>): List<Message>
    suspend fun forDay(start: LocalDateTime, end: LocalDateTime): List<Message>
    suspend fun latestBefore(end: LocalDateTime): LocalDateTime?
    suspend fun page(before: Long): List<Message>
    suspend fun context(before: Long): List<Message>
}
interface ReviewDayStore {
    suspend fun save(day: ReviewDay): ReviewDay
    suspend fun findByTargetDate(date: LocalDate): ReviewDay?
}
interface ReviewQuestionStore {
    suspend fun save(question: ReviewQuestion): ReviewQuestion
    suspend fun findById(id: Long): ReviewQuestion?
    suspend fun forDay(dayId: Long): List<ReviewQuestion>
    suspend fun answered(): List<ReviewQuestion>
}
interface TransactionBoundary {
    suspend fun <T : Any> execute(block: suspend () -> T): T
}
