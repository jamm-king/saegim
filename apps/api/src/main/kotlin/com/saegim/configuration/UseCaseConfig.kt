package com.saegim.configuration

import com.saegim.application.*
import com.saegim.application.port.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import java.time.Clock

@Configuration
class UseCaseConfig {
    @Bean
    @DependsOn("reviewSchemaMigration")
    fun chatService(messages: MessageStore, ai: ChatAi, transactions: TransactionBoundary) = ChatService(messages, ai, transactions)

    @Bean
    @DependsOn("reviewSchemaMigration")
    fun reviewService(days: ReviewDayStore, questions: ReviewQuestionStore, messages: MessageStore,
        ai: ReviewAi, transactions: TransactionBoundary, clock: Clock) = ReviewService(days, questions, messages, ai, transactions, clock)
}
