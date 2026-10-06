package com.saegim.adapter.out.persistence

import com.saegim.application.port.*
import com.saegim.domain.*
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.*

@Component
class R2dbcMessageStore(private val repository: MessageRepository) : MessageStore {
    override suspend fun save(message: Message) = repository.save(message.record()).awaitSingle().domain()
    override suspend fun findById(id: Long) = repository.findById(id).awaitSingleOrNull()?.domain()
    override suspend fun findByRequestId(requestId: String) = repository.findByRequestId(requestId).awaitSingleOrNull()?.domain()
    override suspend fun findByInReplyTo(id: Long) = repository.findByInReplyTo(id).awaitSingleOrNull()?.domain()
    override suspend fun findAllById(ids: List<Long>) = repository.findAllById(ids).collectList().awaitSingle().map { it.domain() }
    override suspend fun forDay(start: LocalDateTime, end: LocalDateTime) = repository.forDay(start, end).collectList().awaitSingle().map { it.domain() }
    override suspend fun latestBefore(end: LocalDateTime) = repository.latestBefore(end).awaitSingleOrNull()
    override suspend fun page(before: Long) = repository.page(before).collectList().awaitSingle().map { it.domain() }
    override suspend fun context(before: Long) = repository.context(before).collectList().awaitSingle().map { it.domain() }
}

@Component
class R2dbcReviewDayStore(private val repository: ReviewDayRepository) : ReviewDayStore {
    override suspend fun save(day: ReviewDay) = repository.save(day.record()).awaitSingle().domain()
    override suspend fun findByTargetDate(date: LocalDate) = repository.findByTargetDate(date).awaitSingleOrNull()?.domain()
}

@Component
class R2dbcReviewQuestionStore(private val repository: ReviewQuestionRepository, private val json: ObjectMapper) : ReviewQuestionStore {
    override suspend fun save(question: ReviewQuestion) = repository.save(question.record(json)).awaitSingle().domain(json)
    override suspend fun findById(id: Long) = repository.findById(id).awaitSingleOrNull()?.domain(json)
    override suspend fun forDay(dayId: Long) = repository.forDay(dayId).collectList().awaitSingle().map { it.domain(json) }
    override suspend fun answered() = repository.answered().collectList().awaitSingle().map { it.domain(json) }
}
private fun MessageEntity.domain() = Message(id = id, requestId = requestId, role = role, content = content, createdAt = createdAt, kind = kind, status = status, inReplyTo = inReplyTo, reviewQuestionId = reviewQuestionId, reviewAction = reviewAction, failureReason = failureReason)
private fun Message.record() = MessageEntity(id = id, requestId = requestId, role = role, content = content, createdAt = createdAt, kind = kind, status = status, inReplyTo = inReplyTo, reviewQuestionId = reviewQuestionId, reviewAction = reviewAction, failureReason = failureReason)
private fun ReviewDayEntity.domain() = ReviewDay(id = id, targetDate = targetDate, status = status, error = error, createdAt = createdAt, currentQuestionId = currentQuestionId, anchorDate = anchorDate, sourceStartDate = sourceStartDate, sourceEndDate = sourceEndDate)
private fun ReviewDay.record() = ReviewDayEntity(id = id, targetDate = targetDate, status = status, error = error, createdAt = createdAt, currentQuestionId = currentQuestionId, anchorDate = anchorDate, sourceStartDate = sourceStartDate, sourceEndDate = sourceEndDate)
private fun ReviewQuestionEntity.domain(json: ObjectMapper) = ReviewQuestion(id = id, reviewDayId = reviewDayId, position = position, question = question, expectedAnswer = expectedAnswer, sourceMessageIds = json.readValue(sourceMessageIds, Array<Long>::class.java).toList(), status = status)
private fun ReviewQuestion.record(json: ObjectMapper) = ReviewQuestionEntity(id = id, reviewDayId = reviewDayId, position = position, question = question, expectedAnswer = expectedAnswer, sourceMessageIds = json.writeValueAsString(sourceMessageIds), status = status)
