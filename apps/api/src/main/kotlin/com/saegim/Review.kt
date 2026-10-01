package com.saegim

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.DependsOn
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Table("review_days")
data class ReviewDay(@Id val id: Long? = null, val targetDate: LocalDate, val status: String,
    val error: String? = null, val createdAt: LocalDateTime, val currentQuestionId: Long? = null)

@Table("review_questions")
data class ReviewQuestion(@Id val id: Long? = null, val reviewDayId: Long, val position: Int,
    val question: String, val expectedAnswer: String, val sourceMessageIds: String, val status: String = "WAITING")

interface ReviewDayRepository : ReactiveCrudRepository<ReviewDay, Long> {
    fun findByTargetDate(targetDate: LocalDate): Mono<ReviewDay>
}
interface ReviewQuestionRepository : ReactiveCrudRepository<ReviewQuestion, Long> {
    @Query("SELECT * FROM review_questions WHERE review_day_id = :dayId ORDER BY position")
    fun forDay(dayId: Long): Flux<ReviewQuestion>
}

// Public views deliberately contain neither expected answers nor source IDs.
data class QuestionView(val id: Long, val number: Int, val prompt: String, val status: String)
data class ReviewView(val targetDate: String, val status: String, val error: String?, val total: Int, val current: QuestionView?)
data class ReviewOutcome(val review: ReviewView, val messages: List<MessageView> = emptyList())
data class ReviewActionRequest(val requestId: UUID, @field:Size(max = 6000) val content: String = "")

object ReviewDates {
    private val seoul = ZoneId.of("Asia/Seoul")
    fun yesterday(clock: Clock): LocalDate = LocalDate.now(clock.withZone(seoul)).minusDays(1)
    fun bounds(date: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        date.atStartOfDay(seoul).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() to
            date.plusDays(1).atStartOfDay(seoul).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
}

@Service
@DependsOn("reviewSchemaMigration")
class ReviewService(
    private val days: ReviewDayRepository, private val questions: ReviewQuestionRepository,
    private val messages: MessageRepository, private val ai: ReviewAi,
    private val transactions: TransactionalOperator, private val json: ObjectMapper, private val clock: Clock,
) {
    private val busy = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(ReviewService::class.java)
    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!busy.compareAndSet(false, true)) throw ResponseStatusException(HttpStatus.CONFLICT, "복습을 처리 중입니다. 잠시 뒤 다시 시도해 주세요.")
        try { return block() } finally { busy.set(false) }
    }
    private suspend fun today(): ReviewDay? = days.findByTargetDate(ReviewDates.yesterday(clock)).awaitSingleOrNull()
    private suspend fun requiredDay(): ReviewDay = today() ?: throw ResponseStatusException(HttpStatus.CONFLICT, "복습을 먼저 준비해 주세요.")
    private suspend fun view(day: ReviewDay?): ReviewView {
        if (day == null) return ReviewView(ReviewDates.yesterday(clock).toString(), "NOT_PREPARED", null, 0, null)
        val rows = questions.forDay(day.id!!).collectList().awaitSingle()
        val current = if (day.status == "ACTIVE") rows.find { it.id == day.currentQuestionId } else null
        return ReviewView(day.targetDate.toString(), day.status, day.error, rows.size,
            current?.let { QuestionView(it.id!!, it.position, it.question, it.status) })
    }
    suspend fun get() = view(today())

    suspend fun prepare(): ReviewView = exclusive {
        var day = today()
        if (day != null && day.status !in setOf("FAILED", "GENERATING")) return@exclusive view(day)
        day = days.save(day?.copy(status = "GENERATING", error = null)
            ?: ReviewDay(targetDate = ReviewDates.yesterday(clock), status = "GENERATING", createdAt = now())).awaitSingle()
        val generating = day
        try {
            val (start, end) = ReviewDates.bounds(generating.targetDate)
            val source = messages.forDay(start, end).collectList().awaitSingle()
            if (source.isEmpty()) return@exclusive view(days.save(generating.copy(status = "NO_CONVERSATION")).awaitSingle())
            val generated = ai.questions(source)
            val saved = transactions.executeAndAwait {
                for ((index, question) in generated.withIndex()) {
                    questions.save(ReviewQuestion(reviewDayId = generating.id!!, position = index + 1,
                        question = question.question, expectedAnswer = question.expectedAnswer,
                        sourceMessageIds = json.writeValueAsString(question.sourceIds))).awaitSingle()
                }
                days.save(generating.copy(status = if (generated.isEmpty()) "EMPTY" else "READY")).awaitSingle()
            }
            view(saved)
        } catch (e: Exception) {
            logger.warn("Review generation failed: {}", e.javaClass.simpleName)
            val reason = if (e is AiUnavailable) e.message else "복습 질문 생성에 실패했습니다. 다시 시도해 주세요."
            view(days.save(generating.copy(status = "FAILED", error = reason?.take(500))).awaitSingle())
        }
    }

    private fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    private suspend fun activate(day: ReviewDay, question: ReviewQuestion): ReviewOutcome {
        val saved = transactions.executeAndAwait {
            val active = questions.save(question.copy(status = "ACTIVE")).awaitSingle()
            val prompt = messages.save(Message(requestId = stableId("review-prompt:${active.id}"), role = "assistant",
                content = active.question, kind = "REVIEW", createdAt = now(), reviewQuestionId = active.id, reviewAction = "QUESTION")).awaitSingle()
            val updated = days.save(day.copy(status = "ACTIVE", currentQuestionId = active.id)).awaitSingle()
            updated to prompt
        }
        return ReviewOutcome(view(saved.first), listOf(saved.second.view()))
    }
    suspend fun start(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status != "READY") return@exclusive ReviewOutcome(view(day))
        activate(day, questions.forDay(day.id!!).collectList().awaitSingle().first())
    }
    suspend fun next(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status != "ACTIVE") return@exclusive ReviewOutcome(view(day))
        val rows = questions.forDay(day.id!!).collectList().awaitSingle()
        if (rows.any { it.id == day.currentQuestionId && it.status == "ACTIVE" }) return@exclusive ReviewOutcome(view(day))
        val next = rows.firstOrNull { it.status == "WAITING" }
        if (next != null) activate(day, next) else ReviewOutcome(view(days.save(day.copy(status = "COMPLETED", currentQuestionId = null)).awaitSingle()))
    }
    suspend fun skip(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status !in setOf("READY", "ACTIVE")) return@exclusive ReviewOutcome(view(day))
        val saved = transactions.executeAndAwait {
            for (question in questions.forDay(day.id!!).collectList().awaitSingle()) {
                if (question.status in setOf("ACTIVE", "WAITING")) questions.save(question.copy(status = "SKIPPED")).awaitSingle()
            }
            val notice = messages.save(Message(requestId = stableId("review-skip:${day.id}"), role = "assistant",
                content = "오늘 복습을 건너뛰었습니다. 일반 대화를 이어갈 수 있어요.", kind = "REVIEW", createdAt = now(), reviewAction = "SKIP")).awaitSingle()
            days.save(day.copy(status = "SKIPPED", currentQuestionId = null)).awaitSingle() to notice
        }
        ReviewOutcome(view(saved.first), listOf(saved.second.view()))
    }

    suspend fun action(id: Long, request: ReviewActionRequest, hint: Boolean): ReviewOutcome = exclusive {
        val question = questions.findById(id).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "복습 질문을 찾을 수 없습니다.")
        val day = requiredDay()
        if (day.id != question.reviewDayId) throw ResponseStatusException(HttpStatus.CONFLICT, "복습 날짜가 바뀌었습니다. 화면을 새로고침해 주세요.")
        val action = if (hint) "HINT" else "ANSWER"
        val content = if (hint) "힌트 요청" else request.content.trim()
        if (content.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "떠오르는 답을 입력해 주세요.")
        var user = messages.findByRequestId(request.requestId.toString()).awaitSingleOrNull()
        if (user != null) {
            if (user.kind != "REVIEW" || user.reviewQuestionId != id || user.reviewAction != action || user.content != content)
                throw ResponseStatusException(HttpStatus.CONFLICT, "같은 요청 ID를 다른 복습 요청에 사용할 수 없습니다.")
            val previous = messages.findByInReplyTo(user.id!!).awaitSingleOrNull()
            if (previous != null) return@exclusive ReviewOutcome(view(day), listOf(user.view(), previous.view()))
        }
        if (day.status != "ACTIVE" || day.currentQuestionId != id || question.status != "ACTIVE")
            throw ResponseStatusException(HttpStatus.CONFLICT, "현재 진행 중인 질문이 아닙니다. 복습 상태를 새로고침해 주세요.")
        user = messages.save(user?.copy(status = "PENDING") ?: Message(requestId = request.requestId.toString(),
            role = "user", content = content, kind = "REVIEW", status = "PENDING", createdAt = now(),
            reviewQuestionId = id, reviewAction = action)).awaitSingle()
        val pending = user
        try {
            val ids = json.readValue(question.sourceMessageIds, Array<Long>::class.java).toList()
            val source = messages.findAllById(ids).collectList().awaitSingle().sortedBy { it.id }
            if (source.size != ids.distinct().size) throw AiUnavailable("복습 질문의 원문 근거를 찾을 수 없습니다.")
            val text = ai.respond(question, source, content, hint)
            val saved = transactions.executeAndAwait {
                val response = messages.save(Message(role = "assistant", content = text, kind = "REVIEW", createdAt = now(),
                    inReplyTo = pending.id, reviewQuestionId = id, reviewAction = if (hint) "HINT" else "FEEDBACK")).awaitSingle()
                val completed = messages.save(pending.copy(status = "COMPLETE")).awaitSingle()
                if (!hint) questions.save(question.copy(status = "ANSWERED")).awaitSingle()
                completed to response
            }
            ReviewOutcome(view(day), listOf(saved.first.view(), saved.second.view()))
        } catch (e: Exception) {
            messages.save(pending.copy(status = "FAILED")).awaitSingle()
            logger.warn("Review response failed: {}", e.javaClass.simpleName)
            val reason = if (e is AiUnavailable) e.message else "복습 응답을 완료하지 못했습니다."
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "$reason 저장된 답변에서 다시 시도해 주세요.")
        }
    }
    suspend fun retry(messageId: Long): ReviewOutcome {
        val message = messages.findById(messageId).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "답변을 찾을 수 없습니다.")
        if (message.kind != "REVIEW" || message.role != "user" || message.reviewQuestionId == null || message.requestId == null || message.reviewAction !in setOf("HINT", "ANSWER"))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "다시 시도할 복습 답변이 아닙니다.")
        return action(message.reviewQuestionId, ReviewActionRequest(UUID.fromString(message.requestId), message.content), message.reviewAction == "HINT")
    }
}

@RestController
@RequestMapping("/api/review")
class ReviewController(private val review: ReviewService) {
    @GetMapping suspend fun get() = review.get()
    @PostMapping("/prepare") suspend fun prepare() = review.prepare()
    @PostMapping("/start") suspend fun start() = review.start()
    @PostMapping("/next") suspend fun next() = review.next()
    @PostMapping("/skip") suspend fun skip() = review.skip()
    @PostMapping("/questions/{id}/answer") suspend fun answer(@PathVariable id: Long, @Valid @RequestBody request: ReviewActionRequest) = review.action(id, request, false)
    @PostMapping("/questions/{id}/hint") suspend fun hint(@PathVariable id: Long, @Valid @RequestBody request: ReviewActionRequest) = review.action(id, request, true)
    @PostMapping("/messages/{id}/retry") suspend fun retry(@PathVariable id: Long) = review.retry(id)
}
