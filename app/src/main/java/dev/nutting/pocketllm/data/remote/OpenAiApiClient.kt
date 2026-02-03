package dev.nutting.pocketllm.data.remote

import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatCompletionResponse
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ModelsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class OpenAiApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun createClient(timeoutSeconds: Long): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@OpenAiApiClient.json)
        }
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutSeconds * 1000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 60,
    ): List<ModelInfo> {
        val client = createClient(timeoutSeconds)
        return try {
            val response: ModelsResponse = client.get("${baseUrl.trimEnd('/')}/v1/models") {
                apiKey?.let { headers { append("Authorization", "Bearer $it") } }
            }.body()
            response.data
        } finally {
            client.close()
        }
    }

    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 60,
        request: ChatCompletionRequest,
    ): ChatCompletionResponse {
        val client = createClient(timeoutSeconds)
        return try {
            client.post("${baseUrl.trimEnd('/')}/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                apiKey?.let { headers { append("Authorization", "Bearer $it") } }
                setBody(request.copy(stream = false))
            }.body()
        } finally {
            client.close()
        }
    }

    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 60,
        request: ChatCompletionRequest,
    ): Flow<ChatCompletionChunk> = flow {
        val client = createClient(timeoutSeconds)
        try {
            client.sse(
                urlString = "${baseUrl.trimEnd('/')}/v1/chat/completions",
                request = {
                    method = io.ktor.http.HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    apiKey?.let { headers { append("Authorization", "Bearer $it") } }
                    setBody(json.encodeToString(ChatCompletionRequest.serializer(), request.copy(stream = true)))
                },
            ) {
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    if (data == "[DONE]") return@collect
                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                    emit(chunk)
                }
            }
        } finally {
            client.close()
        }
    }
}
