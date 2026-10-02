package com.saegim.application

import com.saegim.domain.*
import com.saegim.application.port.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class ReviewService(
    private val days: ReviewDayStore, private val questions: ReviewQuestionStore,
    private val messages: MessageStore, private val ai: ReviewAi,
    private val transactions: TransactionBoundary, private val clock: Clock,
) : ReviewUseCase {
    private val busy = AtomicBoolean(false)
    private val logger = Logger.getLogger(ReviewService::class.java.name)
    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!busy.compareAndSet(false, true)) throw ApplicationFailure(FailureKind.CONFLICT, "복습을 처리 중입니다. 잠시 뒤 다시 시도해 주세요.")
        try { return block() } finally { busy.set(false) }
    }
    private suspend fun today(): ReviewDay? = days.findByTargetDate(ReviewDates.yesterday(clock))
    private suspend fun requiredDay(): ReviewDay = today() ?: throw ApplicationFailure(FailureKind.CONFLICT, "복습을 먼저 준비해 주세요.")
    private suspend fun view(day: ReviewDay?): ReviewView {
        if (day == null) return ReviewView(ReviewDates.yesterday(clock).toString(), "NOT_PREPARED", null, 0, null)
        val rows = questions.forDay(day.id!!)
        val current = if (day.status == "ACTIVE") rows.find { it.id == day.currentQuestionId } else null
        return ReviewView(day.targetDate.toString(), day.status, day.error, rows.size,
            current?.let { QuestionView(it.id!!, it.position, it.question, it.status) },
            day.anchorDate?.toString(), day.sourceStartDate?.toString(), day.sourceEndDate?.toString())
    }
    override suspend fun get() = view(today())

    override suspend fun prepare(): ReviewView = exclusive {
        var day = today()
        if (day != null && day.status !in setOf("FAILED", "GENERATING")) return@exclusive view(day)
        day = days.save(day?.copy(status = "GENERATING", error = null)
            ?: ReviewDay(targetDate = ReviewDates.yesterday(clock), status = "GENERATING", createdAt = now()))
        var generating: ReviewDay = requireNotNull(day)
        try {
            val todayStart = ReviewDates.bounds(ReviewDates.yesterday(clock)).second
            val latest = messages.latestBefore(todayStart)
            val anchor = latest?.let(ReviewDates::localDate)
            generating = days.save(generating.copy(anchorDate = anchor, sourceStartDate = null, sourceEndDate = null))
            if (anchor == null) return@exclusive view(days.save(generating.copy(status = "NO_CONVERSATION")))
            val reviewedIds = questions.answered()
                .flatMap { it.sourceMessageIds }.toSet()
            val source = mutableListOf<Message>()
            var generated = emptyList<GeneratedQuestion>()
            for (date in ReviewDates.candidates(anchor)) {
                val (start, end) = ReviewDates.bounds(date)
                val rows = messages.forDay(start, end)
                // Keep each eligible day's full context, but only ask about unreviewed evidence.
                if (rows.none { it.role == "assistant" && it.id !in reviewedIds }) continue
                source.addAll(rows)
                source.sortWith(compareBy<Message> { it.createdAt }.thenBy { it.id })
                generating = days.save(generating.copy(sourceStartDate = date, sourceEndDate = generating.sourceEndDate ?: date))
                ReviewRules.checkBudget(source)
                generated = ai.questions(source, reviewedIds)
                ReviewRules.validate(generated, source, reviewedIds)
                if (generated.isNotEmpty()) break
            }
            if (source.isEmpty()) return@exclusive view(days.save(generating.copy(status = "NO_CONVERSATION")))
            val saved = transactions.execute {
                for ((index, question) in generated.withIndex()) {
                    questions.save(ReviewQuestion(reviewDayId = generating.id!!, position = index + 1,
                        question = question.question, expectedAnswer = question.expectedAnswer,
                        sourceMessageIds = question.sourceIds))
                }
                days.save(generating.copy(status = if (generated.isEmpty()) "EMPTY" else "READY"))
            }
            view(saved)
        } catch (e: Exception) {
            logger.warning("Review generation failed: ${e.javaClass.simpleName}")
            val reason = if (e is AiUnavailable) e.message else "복습 질문 생성에 실패했습니다. 다시 시도해 주세요."
            view(days.save(generating.copy(status = "FAILED", error = reason?.take(500))))
        }
    }

    private fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    private suspend fun activate(day: ReviewDay, question: ReviewQuestion): ReviewOutcome {
        val saved = transactions.execute {
            val active = questions.save(question.copy(status = "ACTIVE"))
            val prompt = messages.save(Message(requestId = stableId("review-prompt:${active.id}"), role = "assistant",
                content = active.question, kind = "REVIEW", createdAt = now(), reviewQuestionId = active.id, reviewAction = "QUESTION"))
            val updated = days.save(day.copy(status = "ACTIVE", currentQuestionId = active.id))
            updated to prompt
        }
        return ReviewOutcome(view(saved.first), listOf(saved.second.view()))
    }
    override suspend fun start(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status != "READY") return@exclusive ReviewOutcome(view(day))
        activate(day, questions.forDay(day.id!!).first())
    }
    override suspend fun next(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status != "ACTIVE") return@exclusive ReviewOutcome(view(day))
        val rows = questions.forDay(day.id!!)
        if (rows.any { it.id == day.currentQuestionId && it.status == "ACTIVE" }) return@exclusive ReviewOutcome(view(day))
        val next = rows.firstOrNull { it.status == "WAITING" }
        if (next != null) activate(day, next) else ReviewOutcome(view(days.save(day.copy(status = "COMPLETED", currentQuestionId = null))))
    }
    override suspend fun skip(): ReviewOutcome = exclusive {
        val day = requiredDay()
        if (day.status !in setOf("READY", "ACTIVE")) return@exclusive ReviewOutcome(view(day))
        val saved = transactions.execute {
            for (question in questions.forDay(day.id!!)) {
                if (question.status in setOf("ACTIVE", "WAITING")) questions.save(question.copy(status = "SKIPPED"))
            }
            val notice = messages.save(Message(requestId = stableId("review-skip:${day.id}"), role = "assistant",
                content = "오늘 복습을 건너뛰었습니다. 일반 대화를 이어갈 수 있어요.", kind = "REVIEW", createdAt = now(), reviewAction = "SKIP"))
            days.save(day.copy(status = "SKIPPED", currentQuestionId = null)) to notice
        }
        ReviewOutcome(view(saved.first), listOf(saved.second.view()))
    }

    override suspend fun action(id: Long, request: ReviewActionRequest, hint: Boolean): ReviewOutcome = exclusive {
        val question = questions.findById(id) ?: throw ApplicationFailure(FailureKind.NOT_FOUND, "복습 질문을 찾을 수 없습니다.")
        val day = requiredDay()
        if (day.id != question.reviewDayId) throw ApplicationFailure(FailureKind.CONFLICT, "복습 날짜가 바뀌었습니다. 화면을 새로고침해 주세요.")
        val action = if (hint) "HINT" else "ANSWER"
        val content = if (hint) "힌트 요청" else request.content.trim()
        if (content.isBlank()) throw ApplicationFailure(FailureKind.BAD_REQUEST, "떠오르는 답을 입력해 주세요.")
        var user = messages.findByRequestId(request.requestId.toString())
        if (user != null) {
            if (user.kind != "REVIEW" || user.reviewQuestionId != id || user.reviewAction != action || user.content != content)
                throw ApplicationFailure(FailureKind.CONFLICT, "같은 요청 ID를 다른 복습 요청에 사용할 수 없습니다.")
            val previous = messages.findByInReplyTo(user.id!!)
            if (previous != null) return@exclusive ReviewOutcome(view(day), listOf(user.view(), previous.view()))
        }
        if (day.status != "ACTIVE" || day.currentQuestionId != id || question.status != "ACTIVE")
            throw ApplicationFailure(FailureKind.CONFLICT, "현재 진행 중인 질문이 아닙니다. 복습 상태를 새로고침해 주세요.")
        user = messages.save(user?.copy(status = "PENDING") ?: Message(requestId = request.requestId.toString(),
            role = "user", content = content, kind = "REVIEW", status = "PENDING", createdAt = now(),
            reviewQuestionId = id, reviewAction = action))
        val pending = user
        try {
            val ids = question.sourceMessageIds
            val source = messages.findAllById(ids).sortedBy { it.id }
            if (source.size != ids.distinct().size) throw AiUnavailable("복습 질문의 원문 근거를 찾을 수 없습니다.")
            ReviewRules.checkBudget(source)
            val text = ai.respond(question, source, content, hint)
            val saved = transactions.execute {
                val response = messages.save(Message(role = "assistant", content = text, kind = "REVIEW", createdAt = now(),
                    inReplyTo = pending.id, reviewQuestionId = id, reviewAction = if (hint) "HINT" else "FEEDBACK"))
                val completed = messages.save(pending.copy(status = "COMPLETE"))
                if (!hint) questions.save(question.copy(status = "ANSWERED"))
                completed to response
            }
            ReviewOutcome(view(day), listOf(saved.first.view(), saved.second.view()))
        } catch (e: Exception) {
            messages.save(pending.copy(status = "FAILED"))
            logger.warning("Review response failed: ${e.javaClass.simpleName}")
            val reason = if (e is AiUnavailable) e.message else "복습 응답을 완료하지 못했습니다."
            throw ApplicationFailure(FailureKind.BAD_GATEWAY, "$reason 저장된 답변에서 다시 시도해 주세요.")
        }
    }
    override suspend fun retry(messageId: Long): ReviewOutcome {
        val message = messages.findById(messageId) ?: throw ApplicationFailure(FailureKind.NOT_FOUND, "답변을 찾을 수 없습니다.")
        if (message.kind != "REVIEW" || message.role != "user" || message.reviewQuestionId == null || message.requestId == null || message.reviewAction !in setOf("HINT", "ANSWER"))
            throw ApplicationFailure(FailureKind.BAD_REQUEST, "다시 시도할 복습 답변이 아닙니다.")
        return action(message.reviewQuestionId, ReviewActionRequest(UUID.fromString(message.requestId), message.content), message.reviewAction == "HINT")
    }
}
