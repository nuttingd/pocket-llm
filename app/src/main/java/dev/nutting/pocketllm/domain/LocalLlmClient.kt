package dev.nutting.pocketllm.domain

import android.util.Log
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.llm.LlmEngine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Bridges the local LLM engine with the chat system, producing the same
 * streaming ChatCompletionChunk format as the OpenAI API client.
 */
class LocalLlmClient(
    private val llmEngine: LlmEngine,
    private val localModelStore: LocalModelStore,
    private val modelsDir: File,
    private val apkPath: String,
) {
    companion object {
        private const val TAG = "LocalLlmClient"
        /** Sentinel server ID used to identify local model usage. */
        const val LOCAL_SERVER_ID = "__local__"
    }

    private var loadedModelId: String? = null
    private var loadedGpuPercent: Int = -1
    private var initialized = false

    suspend fun ensureModelLoaded(modelId: String) {
        val model = localModelStore.getById(modelId)
            ?: throw IllegalStateException("Local model not found: $modelId")

        val gpuPercent = localModelStore.gpuOffloadPercent.first()

        // Already loaded with same settings
        if (llmEngine.isReady() && loadedModelId == modelId && loadedGpuPercent == gpuPercent) {
            return
        }

        // Unload previous model if settings changed
        if (llmEngine.isReady()) {
            llmEngine.unload()
        }

        if (!initialized) {
            llmEngine.init(apkPath)
            initialized = true
        }

        val modelPath = File(modelsDir, model.modelFileName).absolutePath
        val projectorPath = if (model.projectorFileName.isNotEmpty()) {
            File(modelsDir, model.projectorFileName).absolutePath
        } else ""
        Log.i(TAG, "Loading local model: $modelPath (GPU: $gpuPercent%, ctx: ${model.contextWindowSize})")
        llmEngine.loadModel(
            modelPath,
            projectorPath = projectorPath,
            gpuOffloadPercent = gpuPercent,
            contextSize = model.contextWindowSize,
        )
        loadedModelId = modelId
        loadedGpuPercent = gpuPercent
    }

    fun getLoadedModelName(): String? {
        return if (llmEngine.isReady()) llmEngine.modelName() else null
    }

    /**
     * Stream chat completions from the local LLM, producing ChatCompletionChunk
     * events in the same format as the OpenAI streaming API.
     */
    fun streamChatCompletion(
        messages: List<ChatMessage>,
        temperature: Float?,
        maxTokens: Int?,
        topP: Float?,
    ): Flow<ChatCompletionChunk> = flow {
        // Convert ChatMessages to JSON for the native layer
        val messagesJson = buildJsonArray {
            for (msg in messages) {
                add(buildJsonObject {
                    put("role", msg.role)
                    put("content", when (val content = msg.content) {
                        is ChatContent.Text -> content.text
                        is ChatContent.Parts -> content.parts.joinToString("\n") { part ->
                            when (part) {
                                is dev.nutting.pocketllm.data.remote.model.ContentPart.TextPart -> part.text
                                is dev.nutting.pocketllm.data.remote.model.ContentPart.ImagePart -> "[image]"
                            }
                        }
                    })
                })
            }
        }.toString()

        // Collect streaming progress from the engine
        val collectorJob = coroutineScope {
            val tokenBuffer = StringBuilder()
            val progressJob = launch {
                llmEngine.progress.collect { progress ->
                    if (progress.tokenText.isNotEmpty()) {
                        // Emit each token as a streaming chunk
                        val chunk = ChatCompletionChunk(
                            id = "local",
                            model = loadedModelId ?: "local",
                            choices = listOf(
                                dev.nutting.pocketllm.data.remote.model.ChunkChoice(
                                    index = 0,
                                    delta = dev.nutting.pocketllm.data.remote.model.Delta(
                                        content = progress.tokenText,
                                    ),
                                    finishReason = null,
                                )
                            ),
                        )
                        emit(chunk)
                    }
                    if (progress.phase == "complete") {
                        // Emit final chunk with finish reason
                        val finalChunk = ChatCompletionChunk(
                            id = "local",
                            model = loadedModelId ?: "local",
                            choices = listOf(
                                dev.nutting.pocketllm.data.remote.model.ChunkChoice(
                                    index = 0,
                                    delta = dev.nutting.pocketllm.data.remote.model.Delta(),
                                    finishReason = "stop",
                                )
                            ),
                        )
                        emit(finalChunk)
                    }
                }
            }

            // Run inference (blocking)
            llmEngine.inferChat(
                messagesJson = messagesJson,
                maxTokens = maxTokens ?: 2048,
                temperature = temperature ?: 0.7f,
                topP = topP ?: 0.95f,
            )

            progressJob.cancel()
        }
    }

    fun cancel() {
        llmEngine.cancel()
    }

    fun unload() {
        llmEngine.unload()
        loadedModelId = null
        loadedGpuPercent = -1
    }
}
