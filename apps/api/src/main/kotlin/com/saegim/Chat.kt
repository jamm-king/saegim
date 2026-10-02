package com.saegim

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Table("messages")
data class Message(
    @Id val id: Long? = null,
    val requestId: String? = null,
    val role: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    val kind: String = "CHAT",
    val status: String = "COMPLETE",
    val inReplyTo: Long? = null,
    val reviewQuestionId: Long? = null,
    val reviewAction: String? = null,
) {
    fun view() = MessageView(id!!, role, content, createdAt.toInstant(ZoneOffset.UTC).toString(), status, kind, reviewQuestionId)
}

data class MessageView(val id: Long, val role: String, val content: String, val createdAt: String, val status: String, val kind: String, val reviewQuestionId: Long?)
data class MessagePage(val messages: List<MessageView>, val nextCursor: Long?)
data class ChatRequest(val requestId: UUID, @field:NotBlank @field:Size(max = 6000) val content: String)
data class Turn(val user: MessageView, val assistant: MessageView)

interface MessageRepository : ReactiveCrudRepository<Message, Long> {
    fun findByRequestId(requestId: String): Mono<Message>
    fun findByInReplyTo(inReplyTo: Long): Mono<Message>

    @Query("SELECT * FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND created_at >= :start AND created_at < :end ORDER BY id")
    fun forDay(start: LocalDateTime, end: LocalDateTime): Flux<Message>

    @Query("SELECT created_at FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND created_at < :end ORDER BY created_at DESC LIMIT 1")
    fun latestBefore(end: LocalDateTime): Mono<LocalDateTime>

    @Query("SELECT * FROM messages WHERE id < :before ORDER BY id DESC LIMIT 21")
    fun page(before: Long): Flux<Message>

    @Query("SELECT * FROM messages WHERE kind = 'CHAT' AND status = 'COMPLETE' AND id < :before ORDER BY id DESC LIMIT 20")
    fun context(before: Long): Flux<Message>
}

@Service
class ChatService(
    private val messages: MessageRepository,
    private val ai: OpenAiClient,
    private val transactions: TransactionalOperator,
) {
    // One API instance for the local single-user prototype. No distributed queue.
    private val busy = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    suspend fun page(before: Long?): MessagePage {
        if (before != null && before <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 페이지 위치입니다.")
        val rows = messages.page(before ?: Long.MAX_VALUE).collectList().awaitSingle()
        val visible = rows.take(20)
        return MessagePage(visible.reversed().map { it.view() }, if (rows.size > 20) visible.last().id else null)
    }

    suspend fun send(request: ChatRequest): Turn {
        if (!busy.compareAndSet(false, true)) throw ResponseStatusException(HttpStatus.CONFLICT, "응답을 생성 중입니다. 잠시 뒤 다시 시도해 주세요.")
        try {
            val requestId = request.requestId.toString()
            val content = request.content.trim()
            if (content.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력해 주세요.")
            var user = messages.findByRequestId(requestId).awaitSingleOrNull()
            if (user != null && user.kind != "CHAT") throw ResponseStatusException(HttpStatus.CONFLICT, "복습 요청 ID를 일반 대화에 사용할 수 없습니다.")
            if (user != null && user.content != content) throw ResponseStatusException(HttpStatus.CONFLICT, "같은 요청 ID에 다른 질문을 보낼 수 없습니다.")
            if (user == null) user = messages.save(Message(requestId = requestId, role = "user", content = content, status = "PENDING")).awaitSingle()
            val existing = messages.findByInReplyTo(user.id!!).awaitSingleOrNull()
            if (existing != null) return Turn(user.view(), existing.view())
            user = messages.save(user.copy(status = "PENDING")).awaitSingle()
            val pending = user
            try {
                val history = messages.context(pending.id!!).collectList().awaitSingle().reversed()
                val answer = ai.reply(history + pending)
                return transactions.executeAndAwait {
                    val assistant = messages.save(Message(role = "assistant", content = answer, inReplyTo = pending.id)).awaitSingle()
                    val completed = messages.save(pending.copy(status = "COMPLETE")).awaitSingle()
                    Turn(completed.view(), assistant.view())
                }
            } catch (e: Exception) {
                messages.save(pending.copy(status = "FAILED")).awaitSingle()
                logger.warn("Chat response failed: {}", e.javaClass.simpleName)
                val reason = if (e is AiUnavailable) e.message else "AI 응답을 완료하지 못했습니다."
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "$reason 저장된 질문에서 다시 시도해 주세요.")
            }
        } finally {
            busy.set(false)
        }
    }
}

@RestController
@RequestMapping("/api")
class ChatController(private val chat: ChatService, private val ai: OpenAiClient, private val messages: MessageRepository) {
    @GetMapping("/messages")
    suspend fun messages(@RequestParam(required = false) before: Long?) = chat.page(before)

    @PostMapping("/chat")
    suspend fun send(@Valid @RequestBody request: ChatRequest) = chat.send(request)

    @PostMapping("/messages/{id}/retry")
    suspend fun retry(@PathVariable id: Long): Turn {
        val user = messages.findById(id).awaitSingleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다.")
        if (user.role != "user" || user.requestId == null || user.kind != "CHAT") throw ResponseStatusException(HttpStatus.BAD_REQUEST, "다시 보낼 일반 질문이 아닙니다.")
        return chat.send(ChatRequest(UUID.fromString(user.requestId), user.content))
    }

    @GetMapping("/settings")
    fun settings() = mapOf("provider" to "OpenAI", "model" to ai.model, "configured" to ai.configured, "mock" to false, "contextMessages" to 20)
}
