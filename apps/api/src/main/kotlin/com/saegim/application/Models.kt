package com.saegim.application

import com.saegim.domain.Message
import java.time.ZoneOffset
import java.util.UUID

data class MessageView(val id: Long, val role: String, val content: String, val createdAt: String, val status: String, val kind: String, val reviewQuestionId: Long?, val failureReason: String? = null)
data class MessagePage(val messages: List<MessageView>, val nextCursor: Long?)
data class ChatRequest(val requestId: UUID, val content: String)
data class Turn(val user: MessageView, val assistant: MessageView)

// Public views deliberately contain neither expected answers nor source IDs.
data class QuestionView(val id: Long, val number: Int, val prompt: String, val status: String)
data class ReviewView(val targetDate: String, val status: String, val error: String?, val total: Int, val current: QuestionView?,
    val anchorDate: String? = null, val sourceStartDate: String? = null, val sourceEndDate: String? = null)
data class ReviewOutcome(val review: ReviewView, val messages: List<MessageView> = emptyList())
data class ReviewActionRequest(val requestId: UUID, val content: String = "")

data class AiSettings(val provider: String, val model: String, val configured: Boolean,
    val mock: Boolean = false, val contextMessages: Int = 20)

fun Message.view() = MessageView(id!!, role, content, createdAt.toInstant(ZoneOffset.UTC).toString(), status, kind, reviewQuestionId, failureReason)
