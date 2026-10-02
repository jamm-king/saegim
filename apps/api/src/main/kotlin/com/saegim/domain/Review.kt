package com.saegim.domain

import java.time.*

data class ReviewDay(val id: Long? = null, val targetDate: LocalDate, val status: String,
    val error: String? = null, val createdAt: LocalDateTime, val currentQuestionId: Long? = null,
    val anchorDate: LocalDate? = null, val sourceStartDate: LocalDate? = null, val sourceEndDate: LocalDate? = null)

data class ReviewQuestion(val id: Long? = null, val reviewDayId: Long, val position: Int,
    val question: String, val expectedAnswer: String, val sourceMessageIds: List<Long>, val status: String = "WAITING")

object ReviewDates {
    private val seoul = ZoneId.of("Asia/Seoul")
    fun yesterday(clock: Clock): LocalDate = LocalDate.now(clock.withZone(seoul)).minusDays(1)
    fun localDate(utc: LocalDateTime): LocalDate = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(seoul).toLocalDate()
    fun candidates(anchor: LocalDate): List<LocalDate> = (0L..2L).map { anchor.minusDays(it) }
    fun bounds(date: LocalDate): Pair<LocalDateTime, LocalDateTime> =
        date.atStartOfDay(seoul).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime() to
            date.plusDays(1).atStartOfDay(seoul).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
}

data class GeneratedQuestion(val question: String, val expectedAnswer: String, val sourceIds: List<Long>)
