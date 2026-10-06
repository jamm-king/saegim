package com.saegim.domain

enum class FailureKind { BAD_REQUEST, NOT_FOUND, CONFLICT, BAD_GATEWAY }
class ApplicationFailure(val kind: FailureKind, message: String) : RuntimeException(message)
enum class AiFailureReason { UNAVAILABLE, OUTPUT_LIMIT, CONTENT_FILTER, INCOMPLETE, EMPTY_RESPONSE, REFUSAL, AUTHENTICATION, MODEL, RATE_LIMIT, HTTP_ERROR, TIMEOUT, CONNECTION, INVALID_RESPONSE }
class AiUnavailable(message: String = "OpenAI 응답을 완료하지 못했습니다.", val reason: AiFailureReason = AiFailureReason.UNAVAILABLE) : RuntimeException(message)
