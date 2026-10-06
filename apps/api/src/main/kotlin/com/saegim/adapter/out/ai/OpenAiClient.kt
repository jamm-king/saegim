package com.saegim.adapter.out.ai

import com.saegim.domain.*
import com.saegim.application.AiSettings
import com.saegim.application.port.ChatAi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import kotlinx.coroutines.reactor.awaitSingle
import java.time.Duration
import java.util.concurrent.TimeoutException

data class AiInput(val role: String, val content: String)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiRequest(val model: String, val instructions: String, val input: List<AiInput>, val store: Boolean = false, val max_output_tokens: Int = 1200, val text: Map<String, Any>? = null, val reasoning: Map<String, String>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiText(val type: String = "", val text: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiOutput(val type: String = "", val content: List<AiText> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiResponse(val status: String = "", val output: List<AiOutput> = emptyList(), val incomplete_details: AiIncompleteDetails? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiIncompleteDetails(val reason: String = "")

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
        return generate(
            """
            당신은 새김의 대화 도우미입니다. 한국어로 명확하게 설명하고, 불확실한 내용은 불확실하다고 말하세요.
            사용자가 이해했거나 습득했다고 단정하지 마세요.
            전체 답변은 1,200토큰 이내를 목표로 간결하게 작성하세요. 핵심 결론부터 설명하고 반복 설명과 긴 서론은 생략하세요.
            예제는 필요한 경우 짧은 예제 하나만 사용하세요. 여러 내용을 묻더라도 각 질문의 핵심을 먼저 답하세요.
            자세한 설명은 후속 질문으로 이어갈 수 있도록 하고, 현재 답변은 문장과 코드 블록을 완결하여 마무리하세요.
            """.trimIndent(),
            messages.map { AiInput(it.role, it.content) },
            // Leave room to finish when the model exceeds the prompt's target length.
            maxTokens = 2400,
        )
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
                val failure = when (reply.statusCode().value()) {
                    401, 403 -> AiUnavailable("OpenAI API 인증 또는 접근 권한을 확인해 주세요.", AiFailureReason.AUTHENTICATION)
                    404 -> AiUnavailable("OpenAI 모델 설정을 확인해 주세요.", AiFailureReason.MODEL)
                    429 -> AiUnavailable("OpenAI 사용 한도 또는 호출 제한에 도달했습니다. 결제·한도를 확인하거나 잠시 뒤 다시 시도해 주세요.", AiFailureReason.RATE_LIMIT)
                    else -> AiUnavailable("OpenAI 요청을 완료하지 못했습니다 (HTTP ${reply.statusCode().value()}).", AiFailureReason.HTTP_ERROR)
                }
                reply.releaseBody().then(reactor.core.publisher.Mono.error(failure))
            }
            .bodyToMono(AiResponse::class.java)
            .timeout(Duration.ofSeconds(60))
            .onErrorMap { error ->
                when (error) {
                    is AiUnavailable -> error
                    is TimeoutException -> AiUnavailable("OpenAI 응답 대기 시간(60초)을 초과했습니다. 잠시 뒤 다시 시도해 주세요.", AiFailureReason.TIMEOUT)
                    is WebClientRequestException -> AiUnavailable("OpenAI 서버에 연결하지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요.", AiFailureReason.CONNECTION)
                    else -> AiUnavailable("OpenAI 응답을 읽지 못했습니다. 잠시 뒤 다시 시도해 주세요.", AiFailureReason.INVALID_RESPONSE)
                }
            }
            .awaitSingle()
        if (response.status != "completed") {
            throw when (response.incomplete_details?.reason) {
                "max_output_tokens" -> AiUnavailable("답변이 출력 한도(${maxTokens}토큰)에 도달해 끝까지 생성되지 않았습니다. 질문을 나누거나 더 짧은 답변을 요청해 주세요.", AiFailureReason.OUTPUT_LIMIT)
                "content_filter" -> AiUnavailable("OpenAI의 콘텐츠 제한으로 답변 생성이 중단됐습니다. 질문 내용을 바꿔 주세요.", AiFailureReason.CONTENT_FILTER)
                else -> AiUnavailable("OpenAI가 완성된 응답을 반환하지 않았습니다. 잠시 뒤 다시 시도해 주세요.", AiFailureReason.INCOMPLETE)
            }
        }
        if (response.output.flatMap { it.content }.any { it.type == "refusal" })
            throw AiUnavailable("OpenAI가 이 요청에 대한 답변을 거절했습니다. 질문 내용을 바꿔 주세요.", AiFailureReason.REFUSAL)
        return response.output.filter { it.type == "message" }.flatMap { it.content }
            .filter { it.type == "output_text" }.joinToString("\n") { it.text }.trim()
            .ifEmpty { throw AiUnavailable("OpenAI 응답에 답변 내용이 없습니다. 다시 시도해 주세요.", AiFailureReason.EMPTY_RESPONSE) }
    }
}
