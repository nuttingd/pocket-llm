package dev.nutting.pocketllm.data.remote

import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatCompletionResponse
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ModelsResponse
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.UnknownHostException

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkUnavailable(cause: Throwable? = null) : ApiException("Network unavailable. Check your connection and try again.", cause)
    class ConnectionRefused(val host: String, cause: Throwable? = null) : ApiException("Could not connect to $host. Verify the server is running.", cause)
    class Unauthorized : ApiException("Authentication failed. Check your API key.")
    class RateLimited(val retryAfterSeconds: Long?) : ApiException(
        if (retryAfterSeconds != null) "Rate limited. Retrying in ${retryAfterSeconds}s..."
        else "Rate limited by server. Try again shortly."
    )
    class ContextLengthExceeded : ApiException("Context length exceeded. Try compacting the conversation or starting a new one.")
    class ServerError(val statusCode: Int, detail: String?) : ApiException("Server error ($statusCode)${detail?.let { ": $it" } ?: ". Try again later."}")
    class RequestTimeout(cause: Throwable? = null) : ApiException("Request timed out. The server may be busy — try again or increase the timeout.", cause)
    class EmptyResponse : ApiException("No response generated. Try again or rephrase your message.")
    class StreamDisconnected(val partialContent: String) : ApiException("Connection lost during response. Partial response preserved.")
}

class OpenAiApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    companion object {
        private const val TAG = "OpenAiApiClient"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1000L
    }

    private fun createClient(timeoutSeconds: Long): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@OpenAiApiClient.json)
        }
        install(HttpTimeout) {
            val millis = timeoutSeconds * 1000
            requestTimeoutMillis = millis
            connectTimeoutMillis = millis
            socketTimeoutMillis = millis
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            try {
                return block()
            } catch (e: ApiException.RateLimited) {
                lastException = e
                val waitMs = (e.retryAfterSeconds?.times(1000))
                    ?: (INITIAL_BACKOFF_MS * (1L shl attempt))
                delay(waitMs.coerceAtMost(30_000))
            } catch (e: ApiException.ServerError) {
                if (e.statusCode in listOf(502, 503, 504) && attempt < MAX_RETRIES - 1) {
                    lastException = e
                    delay(INITIAL_BACKOFF_MS * (1L shl attempt))
                } else {
                    throw e
                }
            }
        }
        throw lastException!!
    }

    private fun mapException(e: Throwable): ApiException {
        Log.e(TAG, "Mapping exception: ${e::class.simpleName}: ${e.message}", e)
        return when (e) {
            is ApiException -> e
            is HttpRequestTimeoutException -> ApiException.RequestTimeout(e)
            is UnknownHostException -> ApiException.NetworkUnavailable(e)
            is ConnectException -> ApiException.ConnectionRefused(e.message ?: "unknown host", e)
            is java.net.SocketTimeoutException -> ApiException.RequestTimeout(e)
            else -> {
                val msg = e.message ?: ""
                when {
                    msg.contains("Unable to resolve host", ignoreCase = true) -> ApiException.NetworkUnavailable(e)
                    msg.contains("Connection refused", ignoreCase = true) -> ApiException.ConnectionRefused("server", e)
                    msg.contains("timeout", ignoreCase = true) -> ApiException.RequestTimeout(e)
                    else -> throw e
                }
            }
        }
    }

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 60,
    ): List<ModelInfo> {
        val client = createClient(timeoutSeconds)
        return try {
            withRetry {
                val response = client.get("${baseUrl.trimEnd('/')}/v1/models") {
                    apiKey?.let { headers { append("Authorization", "Bearer $it") } }
                }
                when (response.status.value) {
                    in 200..299 -> response.body<ModelsResponse>().data
                    401 -> throw ApiException.Unauthorized()
                    429 -> {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                        throw ApiException.RateLimited(retryAfter)
                    }
                    in 500..599 -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                    else -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw mapException(e)
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
            withRetry {
                val response = client.post("${baseUrl.trimEnd('/')}/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    apiKey?.let { headers { append("Authorization", "Bearer $it") } }
                    setBody(request.copy(stream = false))
                }
                when (response.status.value) {
                    in 200..299 -> {
                        val result = response.body<ChatCompletionResponse>()
                        if (result.choices.isEmpty() || result.choices.first().message.content.isNullOrBlank()) {
                            throw ApiException.EmptyResponse()
                        }
                        result
                    }
                    401 -> throw ApiException.Unauthorized()
                    429 -> {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                        throw ApiException.RateLimited(retryAfter)
                    }
                    400 -> {
                        val body = response.bodyAsText()
                        if (body.contains("context length", ignoreCase = true) || body.contains("maximum context", ignoreCase = true)) {
                            throw ApiException.ContextLengthExceeded()
                        }
                        throw ApiException.ServerError(400, body.take(200))
                    }
                    in 500..599 -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                    else -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw mapException(e)
        } finally {
            client.close()
        }
    }

    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long = 60,
        request: ChatCompletionRequest,
    ): Flow<ChatCompletionChunk> = channelFlow {
        val client = createClient(timeoutSeconds)
        try {
            val statement = client.preparePost("${baseUrl.trimEnd('/')}/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                apiKey?.let { headers { append("Authorization", "Bearer $it") } }
                setBody(json.encodeToString(ChatCompletionRequest.serializer(), request.copy(stream = true)))
            }
            statement.execute { response ->
                when (response.status.value) {
                    in 200..299 -> { /* proceed to parse SSE stream */ }
                    401 -> throw ApiException.Unauthorized()
                    429 -> {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                        throw ApiException.RateLimited(retryAfter)
                    }
                    400 -> {
                        val body = response.bodyAsText()
                        if (body.contains("context length", ignoreCase = true) || body.contains("maximum context", ignoreCase = true)) {
                            throw ApiException.ContextLengthExceeded()
                        }
                        throw ApiException.ServerError(400, body.take(200))
                    }
                    in 500..599 -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                    else -> throw ApiException.ServerError(response.status.value, response.bodyAsText().take(200))
                }
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                    send(chunk)
                }
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw mapException(e)
        } finally {
            client.close()
        }
    }
}
