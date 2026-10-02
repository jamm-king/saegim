package com.saegim.application.port

import com.saegim.application.*

interface ChatUseCase {
    suspend fun page(before: Long?): MessagePage
    suspend fun send(request: ChatRequest): Turn
    suspend fun retry(id: Long): Turn
    fun settings(): AiSettings
}
interface ReviewUseCase {
    suspend fun get(): ReviewView
    suspend fun prepare(): ReviewView
    suspend fun start(): ReviewOutcome
    suspend fun next(): ReviewOutcome
    suspend fun skip(): ReviewOutcome
    suspend fun action(id: Long, request: ReviewActionRequest, hint: Boolean): ReviewOutcome
    suspend fun retry(messageId: Long): ReviewOutcome
}
