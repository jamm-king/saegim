package com.saegim.adapter.out.persistence

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.*
interface MessageRepository : ReactiveCrudRepository<MessageEntity, Long> {
    fun findByRequestId(requestId: String): Mono<MessageEntity>
    fun findByInReplyTo(inReplyTo: Long): Mono<MessageEntity>

    @Query("SELECT * FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND created_at >= :start AND created_at < :end ORDER BY id")
    fun forDay(start: LocalDateTime, end: LocalDateTime): Flux<MessageEntity>

    @Query("SELECT created_at FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND created_at < :end ORDER BY created_at DESC LIMIT 1")
    fun latestBefore(end: LocalDateTime): Mono<LocalDateTime>

    @Query("SELECT * FROM messages WHERE id < :before ORDER BY id DESC LIMIT 21")
    fun page(before: Long): Flux<MessageEntity>

    @Query("SELECT * FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND id < :before ORDER BY id DESC LIMIT 20")
    fun context(before: Long): Flux<MessageEntity>
}

interface ReviewDayRepository : ReactiveCrudRepository<ReviewDayEntity, Long> {
    fun findByTargetDate(targetDate: LocalDate): Mono<ReviewDayEntity>
}
interface ReviewQuestionRepository : ReactiveCrudRepository<ReviewQuestionEntity, Long> {
    @Query("SELECT * FROM review_questions WHERE review_day_id = :dayId ORDER BY position")
    fun forDay(dayId: Long): Flux<ReviewQuestionEntity>

    @Query("SELECT * FROM review_questions WHERE status = 'ANSWERED'")
    fun answered(): Flux<ReviewQuestionEntity>
}
