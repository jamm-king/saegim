package com.saegim.adapter.out.persistence

import com.saegim.application.port.TransactionBoundary
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

@Component
class R2dbcTransactionBoundary(private val transactions: TransactionalOperator) : TransactionBoundary {
    override suspend fun <T : Any> execute(block: suspend () -> T): T = transactions.executeAndAwait { block() }
}
