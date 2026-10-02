package com.saegim.adapter.`in`.web

import com.saegim.domain.ApplicationFailure
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApplicationFailure::class)
    fun application(error: ApplicationFailure): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.kind.name), error.message ?: "요청을 완료하지 못했습니다.")

    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(error.statusCode, error.reason ?: "요청을 완료하지 못했습니다.")

    @ExceptionHandler(WebExchangeBindException::class, ServerWebInputException::class)
    fun invalid(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "질문은 1~6,000자로 입력해 주세요. 요청 형식도 확인해 주세요.")

    @ExceptionHandler(Exception::class)
    fun unexpected(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "서버 요청을 완료하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")
}
