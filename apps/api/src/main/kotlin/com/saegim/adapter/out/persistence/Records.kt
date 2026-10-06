package com.saegim.adapter.out.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.*

@Table("messages")
data class MessageEntity(
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
    val failureReason: String? = null,
)

@Table("review_days")
data class ReviewDayEntity(@Id val id: Long? = null, val targetDate: LocalDate, val status: String,
    val error: String? = null, val createdAt: LocalDateTime, val currentQuestionId: Long? = null,
    val anchorDate: LocalDate? = null, val sourceStartDate: LocalDate? = null, val sourceEndDate: LocalDate? = null)

@Table("review_questions")
data class ReviewQuestionEntity(@Id val id: Long? = null, val reviewDayId: Long, val position: Int,
    val question: String, val expectedAnswer: String, val sourceMessageIds: String, val status: String = "WAITING")
