package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatCompletionResponse
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

class RemoteInferenceProvider(
    private val apiClient: OpenAiApiClient,
    private val baseUrl: String,
    private val apiKey: String?,
    private val timeoutSeconds: Long,
) : InferenceProvider {

    private var currentJob: Job? = null

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        apiClient.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = apiKey,
            timeoutSeconds = timeoutSeconds,
            request = request,
        ).onStart {
            currentJob = currentCoroutineContext()[Job]
        }

    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse =
        apiClient.chatCompletion(
            baseUrl = baseUrl,
            apiKey = apiKey,
            timeoutSeconds = timeoutSeconds,
            request = request,
        )

    override suspend fun fetchModels(): List<ModelInfo> =
        apiClient.fetchModels(
            baseUrl = baseUrl,
            apiKey = apiKey,
            timeoutSeconds = timeoutSeconds,
        )

    override fun cancel() {
        currentJob?.cancel()
        currentJob = null
    }
}
