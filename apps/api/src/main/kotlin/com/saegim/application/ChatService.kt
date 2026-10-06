package com.saegim.application

import com.saegim.domain.*
import com.saegim.application.port.*
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class ChatService(
    private val messages: MessageStore,
    private val ai: ChatAi,
    private val transactions: TransactionBoundary,
) : ChatUseCase {
    // One API instance for the local single-user prototype. No distributed queue.
    private val busy = AtomicBoolean(false)
    private val logger = Logger.getLogger(ChatService::class.java.name)

    override suspend fun page(before: Long?): MessagePage {
        if (before != null && before <= 0) throw ApplicationFailure(FailureKind.BAD_REQUEST, "잘못된 페이지 위치입니다.")
        val rows = messages.page(before ?: Long.MAX_VALUE)
        val visible = rows.take(20)
        return MessagePage(visible.reversed().map { it.view() }, if (rows.size > 20) visible.last().id else null)
    }

    override suspend fun send(request: ChatRequest): Turn {
        if (!busy.compareAndSet(false, true)) throw ApplicationFailure(FailureKind.CONFLICT, "응답을 생성 중입니다. 잠시 뒤 다시 시도해 주세요.")
        try {
            val requestId = request.requestId.toString()
            val content = request.content.trim()
            if (content.isEmpty()) throw ApplicationFailure(FailureKind.BAD_REQUEST, "질문을 입력해 주세요.")
            var user = messages.findByRequestId(requestId)
            if (user != null && user.kind != "CHAT") throw ApplicationFailure(FailureKind.CONFLICT, "복습 요청 ID를 일반 대화에 사용할 수 없습니다.")
            if (user != null && user.content != content) throw ApplicationFailure(FailureKind.CONFLICT, "같은 요청 ID에 다른 질문을 보낼 수 없습니다.")
            if (user == null) user = messages.save(Message(requestId = requestId, role = "user", content = content, status = "PENDING"))
            val existing = messages.findByInReplyTo(user.id!!)
            if (existing != null) return Turn(user.view(), existing.view())
            user = messages.save(user.copy(status = "PENDING", failureReason = null))
            val pending = user
            try {
                val history = messages.context(pending.id!!).reversed()
                val context = history + pending
                if (context.sumOf { it.content.length.toLong() } > 60000) throw AiUnavailable("최근 대화 맥락이 60,000자 제한을 넘었습니다. 입력 범위를 조정해야 합니다.")
                val answer = ai.reply(context)
                return transactions.execute {
                    val assistant = messages.save(Message(role = "assistant", content = answer, inReplyTo = pending.id))
                    val completed = messages.save(pending.copy(status = "COMPLETE"))
                    Turn(completed.view(), assistant.view())
                }
            } catch (e: Exception) {
                val reason = if (e is AiUnavailable) e.message else "AI 응답을 완료하지 못했습니다."
                messages.save(pending.copy(status = "FAILED", failureReason = reason?.take(500)))
                logger.warning("Chat response failed: messageId=${pending.id} ${e.failureLog()}")
                throw ApplicationFailure(FailureKind.BAD_GATEWAY, "$reason 저장된 질문에서 다시 시도해 주세요.")
            }
        } finally {
            busy.set(false)
        }
    }
    override suspend fun retry(id: Long): Turn {
        val user = messages.findById(id) ?: throw ApplicationFailure(FailureKind.NOT_FOUND, "질문을 찾을 수 없습니다.")
        if (user.role != "user" || user.requestId == null || user.kind != "CHAT") throw ApplicationFailure(FailureKind.BAD_REQUEST, "다시 보낼 일반 질문이 아닙니다.")
        return send(ChatRequest(UUID.fromString(user.requestId), user.content))
    }

    override fun settings() = ai.settings
}
