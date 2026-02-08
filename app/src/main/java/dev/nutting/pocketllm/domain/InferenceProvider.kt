package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatCompletionResponse
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import kotlinx.coroutines.flow.Flow

interface InferenceProvider {

    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk>

    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse

    suspend fun fetchModels(): List<ModelInfo>

    fun cancel()
}
