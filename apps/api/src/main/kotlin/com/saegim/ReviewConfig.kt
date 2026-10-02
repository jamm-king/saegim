package com.saegim

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@Configuration
class ReviewConfig {
    @Bean
    fun reviewClock(@Value("\${saegim.test-now:}") fixed: String): Clock =
        if (fixed.isBlank()) Clock.systemUTC() else Clock.fixed(Instant.parse(fixed), ZoneId.of("UTC"))

    @Bean
    @DependsOnDatabaseInitialization
    fun reviewSchemaMigration(db: DatabaseClient) = InitializingBean {
        // Only blocks the startup thread, before review controllers are initialized.
        runBlocking {
            for ((name, type) in listOf("review_question_id" to "BIGINT NULL", "review_action" to "VARCHAR(16) NULL")) {
                val exists = db.sql("SELECT COUNT(*) AS n FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'messages' AND column_name = :name")
                    .bind("name", name).map { row, _ -> (row.get("n") as Number).toInt() }.one().awaitSingle()
                if (exists == 0) db.sql("ALTER TABLE messages ADD COLUMN $name $type").fetch().rowsUpdated().awaitSingle()
            }
            for (name in listOf("anchor_date", "source_start_date", "source_end_date")) {
                val exists = db.sql("SELECT COUNT(*) AS n FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'review_days' AND column_name = :name")
                    .bind("name", name).map { row, _ -> (row.get("n") as Number).toInt() }.one().awaitSingle()
                if (exists == 0) db.sql("ALTER TABLE review_days ADD COLUMN $name DATE NULL").fetch().rowsUpdated().awaitSingle()
            }
            // Older successful question sets used exactly target_date; preserve their source label.
            db.sql("UPDATE review_days SET anchor_date = target_date, source_start_date = target_date, source_end_date = target_date WHERE anchor_date IS NULL AND status IN ('READY', 'ACTIVE', 'COMPLETED', 'SKIPPED')")
                .fetch().rowsUpdated().awaitSingle()
        }
    }
}
