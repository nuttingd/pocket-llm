package dev.nutting.pocketllm.domain

import android.util.Log
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.remote.model.ChatCompletionChunk
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.data.remote.model.ChunkChoice
import dev.nutting.pocketllm.data.remote.model.ContentPart
import dev.nutting.pocketllm.data.remote.model.Delta
import dev.nutting.pocketllm.llm.InferenceStatus
import dev.nutting.pocketllm.llm.LlmEngine
import dev.nutting.pocketllm.llm.ModelLoadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

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

        /** Messages JSON for the native engine plus the decoded images its media markers refer to. */
        internal class LocalPrompt(val messagesJson: String, val images: List<ByteArray>)

        /**
         * Most images sent to the model per request. Each one is re-encoded every turn and costs up to
         * ~512 context tokens, so older images beyond this are replaced by "[image]".
         */
        internal const val MAX_IMAGES = 2

        /**
         * Flattens chat messages for the native engine. With [visionEnabled], the last [MAX_IMAGES] image
         * parts in the conversation become media markers and their bytes are collected in order; all
         * other images are replaced by "[image]".
         */
        internal fun buildPrompt(messages: List<ChatMessage>, visionEnabled: Boolean): LocalPrompt {
            val images = mutableListOf<ByteArray>()
            val totalImages = messages.sumOf { msg ->
                (msg.content as? ChatContent.Parts)?.parts?.count { it is ContentPart.ImagePart } ?: 0
            }
            val firstSentImage = if (visionEnabled) (totalImages - MAX_IMAGES).coerceAtLeast(0) else Int.MAX_VALUE
            var imageOrdinal = 0
            val json = buildJsonArray {
                for (msg in messages) {
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", when (val content = msg.content) {
                            is ChatContent.Text -> content.text
                            is ChatContent.Parts -> content.parts.joinToString("\n") { part ->
                                when (part) {
                                    is ContentPart.TextPart -> part.text
                                    is ContentPart.ImagePart -> {
                                        val send = imageOrdinal++ >= firstSentImage
                                        val bytes = if (send) decodeDataUrl(part.imageUrl.url) else null
                                        if (bytes != null) {
                                            images += bytes
                                            LlmEngine.MEDIA_MARKER
                                        } else {
                                            "[image]"
                                        }
                                    }
                                }
                            }
                        })
                    })
                }
            }.toString()
            return LocalPrompt(json, images)
        }

        /** Decodes a base64 `data:` URL; returns null for anything else (remote URLs aren't fetched). */
        internal fun decodeDataUrl(url: String): ByteArray? {
            if (!url.startsWith("data:")) return null
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).endsWith(";base64")) return null
            return try {
                Base64.getDecoder().decode(url.substring(comma + 1))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /** What the engine is doing during a load or reply, for progress display; null when idle. */
    val status: StateFlow<InferenceStatus?> get() = llmEngine.status

    private var loadedModelId: String? = null
    private var loadedGpuPercent: Int = -1
    private var loadedHasProjector = false
    private var initialized = false

    private val _modelState = MutableStateFlow<LocalModelState>(LocalModelState.NotLoaded)

    /** Which model is in memory (or loading / failed to load). */
    val modelState: StateFlow<LocalModelState> = _modelState.asStateFlow()

    private val loadMutex = Mutex()

    /** Loads [modelId] unless it's already loaded with current settings. Throws with a user-facing reason. */
    suspend fun ensureModelLoaded(modelId: String) = loadMutex.withLock { ensureModelLoadedLocked(modelId) }

    private suspend fun ensureModelLoadedLocked(modelId: String) {
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
        loadedModelId = null
        _modelState.value = LocalModelState.Loading(modelId)

        try {
            val modelFile = File(modelsDir, model.modelFileName)
            if (!modelFile.isFile) {
                throw ModelLoadException("The model file is missing. Delete the model and download it again.")
            }
            val projectorFile = model.projectorFileName.takeIf { it.isNotEmpty() }?.let { File(modelsDir, it) }
            if (projectorFile != null && !projectorFile.isFile) {
                throw ModelLoadException("The vision projector file is missing. Delete the model and download it again.")
            }

            if (!initialized) {
                llmEngine.init(apkPath)
                initialized = true
            }

            Log.i(TAG, "Loading local model: ${modelFile.path} (GPU: $gpuPercent%, ctx: ${model.contextWindowSize})")
            llmEngine.loadModel(
                modelFile.absolutePath,
                projectorPath = projectorFile?.absolutePath ?: "",
                gpuOffloadPercent = gpuPercent,
                contextSize = model.contextWindowSize,
            )
            loadedModelId = modelId
            loadedGpuPercent = gpuPercent
            loadedHasProjector = projectorFile != null
            _modelState.value = LocalModelState.Loaded(modelId)
        } catch (e: ModelLoadException) {
            _modelState.value = LocalModelState.Failed(modelId, e.message ?: "Model failed to load")
            throw ModelLoadException("Couldn't load ${model.name}: ${e.message}")
        } catch (e: CancellationException) {
            _modelState.value = LocalModelState.NotLoaded
            throw e
        }
    }

    fun getLoadedModelName(): String? {
        return if (llmEngine.isReady()) llmEngine.modelName() else null
    }

    /**
     * Run a non-streaming chat completion locally and return the full response text.
     */
    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Float,
    ): String {
        val prompt = buildPrompt(messages, loadedHasProjector)
        val result = llmEngine.inferChat(
            messagesJson = prompt.messagesJson,
            images = prompt.images,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95f,
        )
        if (result.startsWith("ERROR: ")) {
            throw RuntimeException(result.removePrefix("ERROR: "))
        }
        return result
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
    ): Flow<ChatCompletionChunk> = channelFlow {
        val prompt = buildPrompt(messages, loadedHasProjector)
        val model = loadedModelId ?: "local"
        val splitter = ThinkTagSplitter()
        suspend fun sendPieces(pieces: List<ThinkTagSplitter.Piece>) {
            for (piece in pieces) {
                val delta = if (piece.thinking) Delta(reasoningContent = piece.text) else Delta(content = piece.text)
                send(ChatCompletionChunk(id = "local", model = model, choices = listOf(ChunkChoice(index = 0, delta = delta))))
            }
        }

        // Tokens arrive on the inference thread; queue them and forward from this coroutine in order
        val tokens = Channel<String>(Channel.UNLIMITED)
        val forwarder = launch {
            for (text in tokens) sendPieces(splitter.feed(text))
        }

        val result = try {
            llmEngine.inferChat(
                messagesJson = prompt.messagesJson,
                images = prompt.images,
                maxTokens = maxTokens ?: 2048,
                temperature = temperature ?: 0.7f,
                topP = topP ?: 0.95f,
                onToken = { tokens.trySend(it) },
            )
        } catch (e: Exception) {
            // A native crash poisons the engine; the model has to be reloaded before the next request
            if (llmEngine.state.value is LlmEngine.State.Error) {
                loadedModelId = null
                _modelState.value = LocalModelState.NotLoaded
            }
            throw e
        } finally {
            tokens.close()
        }
        forwarder.join()
        sendPieces(splitter.flush())

        if (result.startsWith("ERROR: ")) {
            val message = result.removePrefix("ERROR: ")
            android.util.Log.e("LocalLlmClient", "Local inference error: $message")
            throw RuntimeException(message)
        }

        // Emit the terminal chunk only after confirming inference completed without error.
        send(
            ChatCompletionChunk(
                id = "local",
                model = loadedModelId ?: "local",
                choices = listOf(ChunkChoice(index = 0, delta = Delta(), finishReason = "stop")),
            )
        )
    }

    fun cancel() {
        llmEngine.cancel()
    }

    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Frees the model in response to memory pressure. Safe to call from the main thread: unloading waits
     * for any in-flight inference to release the native engine, so it runs in the background. With
     * [cancelInFlight] false, an inference in progress is left to finish and nothing is unloaded.
     */
    fun releaseMemory(cancelInFlight: Boolean) {
        if (llmEngine.state.value is LlmEngine.State.Inferring) {
            if (!cancelInFlight) return
            llmEngine.cancel()
        }
        releaseScope.launch { unload() }
    }

    fun unload() {
        llmEngine.unload()
        _modelState.value = LocalModelState.NotLoaded
        loadedModelId = null
        loadedGpuPercent = -1
        loadedHasProjector = false
    }
}
