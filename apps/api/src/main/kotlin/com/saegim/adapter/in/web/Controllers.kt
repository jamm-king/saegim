package com.saegim.adapter.`in`.web

import com.saegim.application.*
import com.saegim.application.port.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class ChatHttpRequest(val requestId: UUID, @field:NotBlank @field:Size(max = 6000) val content: String)
data class ReviewHttpActionRequest(val requestId: UUID, @field:Size(max = 6000) val content: String = "")

@RestController
@RequestMapping("/api")
class ChatController(private val chat: ChatUseCase) {
    @GetMapping("/messages")
    suspend fun messages(@RequestParam(required = false) before: Long?) = chat.page(before)

    @PostMapping("/chat")
    suspend fun send(@Valid @RequestBody request: ChatHttpRequest) = chat.send(ChatRequest(request.requestId, request.content))

    @PostMapping("/messages/{id}/retry")
    suspend fun retry(@PathVariable id: Long) = chat.retry(id)

    @GetMapping("/settings")
    fun settings() = chat.settings()
}

@RestController
@RequestMapping("/api/review")
class ReviewController(private val review: ReviewUseCase) {
    @GetMapping suspend fun get() = review.get()
    @PostMapping("/prepare") suspend fun prepare() = review.prepare()
    @PostMapping("/start") suspend fun start() = review.start()
    @PostMapping("/next") suspend fun next() = review.next()
    @PostMapping("/skip") suspend fun skip() = review.skip()
    @PostMapping("/questions/{id}/answer") suspend fun answer(@PathVariable id: Long, @Valid @RequestBody request: ReviewHttpActionRequest) = review.action(id, ReviewActionRequest(request.requestId, request.content), false)
    @PostMapping("/questions/{id}/hint") suspend fun hint(@PathVariable id: Long, @Valid @RequestBody request: ReviewHttpActionRequest) = review.action(id, ReviewActionRequest(request.requestId, request.content), true)
    @PostMapping("/messages/{id}/retry") suspend fun retry(@PathVariable id: Long) = review.retry(id)
}
