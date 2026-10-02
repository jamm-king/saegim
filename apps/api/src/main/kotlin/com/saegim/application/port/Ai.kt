package com.saegim.application.port

import com.saegim.application.AiSettings
import com.saegim.domain.*

interface ChatAi {
    val settings: AiSettings
    suspend fun reply(messages: List<Message>): String
}
interface ReviewAi {
    suspend fun questions(source: List<Message>, reviewedIds: Set<Long>): List<GeneratedQuestion>
    suspend fun respond(question: ReviewQuestion, source: List<Message>, answer: String, hint: Boolean): String
}
