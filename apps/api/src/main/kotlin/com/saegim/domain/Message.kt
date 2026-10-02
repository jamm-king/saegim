package com.saegim.domain

import java.time.LocalDateTime
import java.time.ZoneOffset

data class Message(
    val id: Long? = null,
    val requestId: String? = null,
    val role: String,
    val content: String,
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    val kind: String = "CHAT",
    val status: String = "COMPLETE",
    val inReplyTo: Long? = null,
    val reviewQuestionId: Long? = null,
    val reviewAction: String? = null,
)
