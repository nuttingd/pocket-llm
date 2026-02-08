package dev.nutting.pocketllm.domain

import android.util.Log
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatCompletionResponse
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.Choice
import dev.nutting.pocketllm.data.remote.model.ChunkChoice
import dev.nutting.pocketllm.data.remote.model.Delta
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ResponseMessage
import dev.nutting.pocketllm.data.remote.model.Usage
import dev.nutting.pocketllm.llm.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class LocalInferenceProvider(
    private val llmEngine: LlmEngine,
    private val localModel: LocalModel,
    private val modelsDir: File,
    private val gpuOffloadPercent: Int = 0,
    private val apkPath: String = "",
) : InferenceProvider {

    companion object {
        private const val TAG = "LocalInferenceProvider"
    }

    private var loadedModelId: String? = null
    private var loadedGpuPercent: Int? = null

    private suspend fun ensureModelLoaded() {
        val needsReload = !llmEngine.isReady() ||
            loadedModelId != localModel.id ||
            loadedGpuPercent != gpuOffloadPercent

        if (!needsReload) return

        if (llmEngine.isReady()) {
            llmEngine.unload()
        }

        llmEngine.init(apkPath)

        val modelFile = File(modelsDir, localModel.modelFileName)

        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.absolutePath}")
        }

        val projectorPath = if (localModel.projectorFileName.isNotEmpty()) {
            val projectorFile = File(modelsDir, localModel.projectorFileName)
            if (projectorFile.exists()) projectorFile.absolutePath else ""
        } else ""

        llmEngine.loadModel(
            modelPath = modelFile.absolutePath,
            projectorPath = projectorPath,
            gpuOffloadPercent = gpuOffloadPercent,
            contextSize = localModel.contextWindowSize,
        )

        if (!llmEngine.isReady()) {
            val state = llmEngine.state.value
            val errorMsg = if (state is LlmEngine.State.Error) state.message else "Failed to load model"
            throw IllegalStateException(errorMsg)
        }

        loadedModelId = localModel.id
        loadedGpuPercent = gpuOffloadPercent
    }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = channelFlow {
        ensureModelLoaded()

        val requestId = "local-${UUID.randomUUID()}"

        // Build prompt from messages
        val prompt = request.messages.joinToString("\n") { msg ->
            val text = when (val c = msg.content) {
                is ChatContent.Text -> c.text
                is ChatContent.Parts -> c.parts.filterIsInstance<dev.nutting.pocketllm.data.remote.model.ContentPart.TextPart>()
                    .joinToString("\n") { it.text }
            }
            "${msg.role}: $text"
        }

        val maxTokens = request.maxTokens ?: 2048
        var tokenCount = 0

        // Collect progress tokens in a separate coroutine
        val progressJob = launch {
            llmEngine.progress.collect { progress ->
                if (progress.tokenText.isNotEmpty()) {
                    tokenCount++
                    send(
                        ChatCompletionChunk(
                            id = requestId,
                            model = localModel.id,
                            choices = listOf(
                                ChunkChoice(
                                    index = 0,
                                    delta = Delta(content = progress.tokenText),
                                    finishReason = null,
                                )
                            ),
                        )
                    )
                }
            }
        }

        try {
            llmEngine.inferText(prompt, maxTokens)
        } finally {
            progressJob.cancel()
        }

        // Emit final chunk with stop and usage
        val estimatedPromptTokens = prompt.length / 4
        send(
            ChatCompletionChunk(
                id = requestId,
                model = localModel.id,
                choices = listOf(
                    ChunkChoice(
                        index = 0,
                        delta = Delta(),
                        finishReason = "stop",
                    )
                ),
                usage = Usage(
                    promptTokens = estimatedPromptTokens,
                    completionTokens = tokenCount,
                    totalTokens = estimatedPromptTokens + tokenCount,
                ),
            )
        )
    }

    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
        ensureModelLoaded()

        val prompt = request.messages.joinToString("\n") { msg ->
            val text = when (val c = msg.content) {
                is ChatContent.Text -> c.text
                is ChatContent.Parts -> c.parts.filterIsInstance<dev.nutting.pocketllm.data.remote.model.ContentPart.TextPart>()
                    .joinToString("\n") { it.text }
            }
            "${msg.role}: $text"
        }

        val maxTokens = request.maxTokens ?: 2048
        val result = llmEngine.inferText(prompt, maxTokens)
        val estimatedPromptTokens = prompt.length / 4
        val completionTokens = result.length / 4

        return ChatCompletionResponse(
            id = "local-${UUID.randomUUID()}",
            model = localModel.id,
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(
                        role = "assistant",
                        content = result,
                    ),
                    finishReason = "stop",
                )
            ),
            usage = Usage(
                promptTokens = estimatedPromptTokens,
                completionTokens = completionTokens,
                totalTokens = estimatedPromptTokens + completionTokens,
            ),
        )
    }

    override suspend fun fetchModels(): List<ModelInfo> = listOf(
        ModelInfo(
            id = localModel.id,
            ownedBy = "local",
        )
    )

    override fun cancel() {
        llmEngine.cancel()
    }
}
