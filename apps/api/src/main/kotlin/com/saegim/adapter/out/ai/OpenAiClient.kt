package com.saegim.adapter.out.ai

import com.saegim.domain.*
import com.saegim.application.AiSettings
import com.saegim.application.port.ChatAi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import kotlinx.coroutines.reactor.awaitSingle
import java.time.Duration

data class AiInput(val role: String, val content: String)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiRequest(val model: String, val instructions: String, val input: List<AiInput>, val store: Boolean = false, val max_output_tokens: Int = 1200, val text: Map<String, Any>? = null, val reasoning: Map<String, String>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiText(val type: String = "", val text: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiOutput(val type: String = "", val content: List<AiText> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiResponse(val status: String = "", val output: List<AiOutput> = emptyList())

@Component
class OpenAiClient(
    @Value("\${saegim.openai.api-key}") private val apiKey: String,
    @Value("\${saegim.openai.model}") val model: String,
    @Value("\${saegim.openai.base-url}") baseUrl: String,
) : ChatAi {
    override val settings get() = AiSettings("OpenAI", model, configured)
    val configured get() = apiKey.isNotBlank()
    private val client = WebClient.builder().baseUrl(baseUrl).build()

    override suspend fun reply(messages: List<Message>): String {
        if (!configured) throw AiUnavailable("서버에 OpenAI API 키를 설정해 주세요.")
        return generate("당신은 새김의 대화 도우미입니다. 한국어로 명확하게 설명하고, 불확실한 내용은 불확실하다고 말하세요. 사용자가 이해했거나 습득했다고 단정하지 마세요.", messages.map { AiInput(it.role, it.content) })
    }

    suspend fun generate(instructions: String, input: List<AiInput>, maxTokens: Int = 1200, format: Map<String, Any>? = null): String {
        if (!configured) throw AiUnavailable("서버에 OpenAI API 키를 설정해 주세요.")
        // Keep Luna's short text workloads within the existing output budget.
        val reasoning = if (model == "gpt-6-luna" || model.startsWith("gpt-6-luna-")) mapOf("effort" to "none") else null
        val request = AiRequest(model, instructions, input, max_output_tokens = maxTokens, text = format?.let { mapOf("format" to it) }, reasoning = reasoning)
        val response = client.post().uri("/responses")
            .header("Authorization", "Bearer $apiKey")
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }) { reply ->
                val reason = when (reply.statusCode().value()) {
                    401, 403 -> "OpenAI API 인증 또는 접근 권한을 확인해 주세요."
                    404 -> "OpenAI 모델 설정을 확인해 주세요."
                    429 -> "OpenAI 사용 한도 또는 호출 제한에 도달했습니다. 결제·한도를 확인하거나 잠시 뒤 다시 시도해 주세요."
                    else -> "OpenAI 요청을 완료하지 못했습니다 (HTTP ${reply.statusCode().value()})."
                }
                reply.releaseBody().then(reactor.core.publisher.Mono.error(AiUnavailable(reason)))
            }
            .bodyToMono(AiResponse::class.java)
            .timeout(Duration.ofSeconds(60))
            .awaitSingle()
        if (response.status != "completed") throw AiUnavailable()
        return response.output.filter { it.type == "message" }.flatMap { it.content }
            .filter { it.type == "output_text" }.joinToString("\n") { it.text }.trim()
            .ifEmpty { throw AiUnavailable() }
    }
}
