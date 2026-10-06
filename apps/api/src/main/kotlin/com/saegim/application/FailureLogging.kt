package com.saegim.application

import com.saegim.domain.AiUnavailable

// Only app-authored AI messages are safe to log; other exception messages can contain secrets.
internal fun Exception.failureLog(): String = if (this is AiUnavailable) {
    "reason=${reason.name} detail=${message}"
} else {
    "reason=INTERNAL_ERROR type=${javaClass.simpleName}"
}
