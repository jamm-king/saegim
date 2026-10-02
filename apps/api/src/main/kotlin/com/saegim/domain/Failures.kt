package com.saegim.domain

enum class FailureKind { BAD_REQUEST, NOT_FOUND, CONFLICT, BAD_GATEWAY }
class ApplicationFailure(val kind: FailureKind, message: String) : RuntimeException(message)
class AiUnavailable(message: String = "OpenAI 응답을 완료하지 못했습니다.") : RuntimeException(message)
