package dev.nutting.pocketllm.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.dao.GeneratedImageDao
import dev.nutting.pocketllm.data.local.dao.ImageJobDao
import dev.nutting.pocketllm.data.local.dao.ParameterPresetDao
import dev.nutting.pocketllm.data.local.dao.ToolDefinitionDao
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.ConversationToolEnabledEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.remote.ApiException
import dev.nutting.pocketllm.data.remote.image.imageJson
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ActiveGeneration
import dev.nutting.pocketllm.domain.ChatManager
import dev.nutting.pocketllm.domain.GenerationManager
import dev.nutting.pocketllm.domain.LocalLlmClient
import dev.nutting.pocketllm.domain.TitleRequest
import dev.nutting.pocketllm.domain.image.ImageJobLauncher
import dev.nutting.pocketllm.domain.image.ImageParameters
import dev.nutting.pocketllm.domain.image.ImageServerInfo
import dev.nutting.pocketllm.domain.image.ModelClassifier
import dev.nutting.pocketllm.domain.image.describeImageReplies
import dev.nutting.pocketllm.util.ImageCompressor
import dev.nutting.pocketllm.util.TokenCounter
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val chatManager: ChatManager,
    private val generationManager: GenerationManager,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val localModelStore: LocalModelStore? = null,
    private val toolDefinitionDao: ToolDefinitionDao? = null,
    private val parameterPresetDao: ParameterPresetDao? = null,
    private val compactionSummaryDao: CompactionSummaryDao? = null,
    private val imageJobLauncher: ImageJobLauncher? = null,
    private val modelClassifier: ModelClassifier? = null,
    private val imageJobDao: ImageJobDao? = null,
    private val generatedImageDao: GeneratedImageDao? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private val VISION_MODEL_KEYWORDS = listOf(
            "vision", "llava", "bakllava", "cogvlm", "fuyu",
            "obsidian", "moondream", "minicpm-v", "internvl",
        )
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var compactionJob: Job? = null
    private var generationJob: Job? = null
    private var imageRepliesJob: Job? = null
    private var isFirstMessage = true
    // The screen calls loadConversation each time it's shown; only the first call for a route loads
    private var loadedRoute: String? = null
    private var hasLoaded = false

    init {
        loadServers()
        loadDefaults()
        loadTools()
        loadPresets()
        observeFontSize()
        observeLocalModels()
        observeImageModels()
    }

    fun loadConversation(conversationId: String?) {
        if (hasLoaded && loadedRoute == conversationId) return
        hasLoaded = true
        loadedRoute = conversationId
        if (conversationId != null) {
            _uiState.update { it.copy(conversationId = conversationId) }
            isFirstMessage = false
            observeConversation(conversationId)
            loadConversationParams(conversationId)
            viewModelScope.launch {
                val conversation = conversationRepository.getById(conversationId).first()
                val modelId = conversation?.lastModelId
                val localModels = _uiState.value.localModels
                if (conversation?.lastServerProfileId == null && modelId != null && localModels.any { it.id == modelId }) {
                    // Restore local model state
                    switchToLocal(modelId)
                } else {
                    loadServerAndModels(
                        preferredServerId = conversation?.lastServerProfileId,
                        preferredModelId = conversation?.lastModelId,
                    )
                }
            }
        } else {
            isFirstMessage = true
            _uiState.update { it.copy(conversationParams = ConversationParameters(), imageParams = ImageParameters()) }
            // Check if there's an active local model to use by default
            viewModelScope.launch {
                val activeId = localModelStore?.activeModelId?.first()
                val localModels = _uiState.value.localModels
                if (activeId != null && localModels.any { it.id == activeId }) {
                    switchToLocal(activeId)
                } else {
                    loadServerAndModels()
                }
            }
        }
    }

    private fun observeFontSize() {
        viewModelScope.launch {
            settingsRepository.getMessageFontSizeSp().collect { sp ->
                _uiState.update { it.copy(messageFontSizeSp = sp) }
            }
        }
    }

    private fun observeLocalModels() {
        viewModelScope.launch {
            chatManager.localStatus.collect { status ->
                _uiState.update { it.copy(localStatus = status) }
            }
        }
        viewModelScope.launch {
            chatManager.localModelState.collect { modelState ->
                _uiState.update { it.copy(localModelState = modelState) }
            }
        }
        viewModelScope.launch {
            localModelStore?.models?.collect { models ->
                val completed = models.filter { it.downloadStatus == DownloadStatus.COMPLETE }
                _uiState.update { it.copy(localModels = completed) }
            }
        }
        viewModelScope.launch {
            localModelStore?.activeModelId?.collect { activeId ->
                _uiState.update { it.copy(activeLocalModelId = activeId) }
            }
        }
    }

    fun switchToLocal(modelId: String) {
        _uiState.update {
            it.copy(
                useLocalModel = true,
                selectedServer = null,
                selectedModelId = modelId,
                availableModels = it.localModels.map { m -> ModelInfo(id = m.id, ownedBy = "local") },
                isLoadingModels = false,
            )
        }
        persistServerAndModel()
    }

    fun switchToRemote() {
        _uiState.update { it.copy(useLocalModel = false) }
        loadServerAndModels()
    }

    private fun loadServers() {
        viewModelScope.launch {
            serverRepository.getAll().collect { servers ->
                _uiState.update { it.copy(availableServers = servers, serversLoaded = true) }
            }
        }
    }

    private fun loadDefaults() {
        viewModelScope.launch {
            val defaults = ConversationParameters(
                systemPrompt = settingsRepository.getDefaultSystemPrompt().first().ifBlank { null },
                temperature = settingsRepository.getDefaultTemperature().first(),
                maxTokens = settingsRepository.getDefaultMaxTokens().first(),
                topP = settingsRepository.getDefaultTopP().first(),
                frequencyPenalty = settingsRepository.getDefaultFrequencyPenalty().first(),
                presencePenalty = settingsRepository.getDefaultPresencePenalty().first(),
            )
            val compactionPct = settingsRepository.getCompactionThresholdPct().first()
            _uiState.update { it.copy(defaultParams = defaults, compactionThresholdPct = compactionPct) }
        }
    }

    private fun loadConversationParams(conversationId: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first() ?: return@launch
            _uiState.update {
                it.copy(
                    conversationParams = ConversationParameters(
                        systemPrompt = conversation.systemPrompt,
                        temperature = conversation.temperature,
                        maxTokens = conversation.maxTokens,
                        topP = conversation.topP,
                        frequencyPenalty = conversation.frequencyPenalty,
                        presencePenalty = conversation.presencePenalty,
                    ),
                    imageParams = decodeImageParams(conversation.imageParamsJson),
                )
            }
        }
    }

    private fun loadServerAndModels(
        preferredServerId: String? = null,
        preferredModelId: String? = null,
    ) {
        viewModelScope.launch {
            val serverId = preferredServerId?.takeIf { it.isNotBlank() }
                ?: settingsRepository.getLastActiveServerId().first()
            if (serverId.isBlank()) return@launch
            val server = serverRepository.getById(serverId).first() ?: return@launch
            _uiState.update { it.copy(selectedServer = server, isLoadingModels = true) }

            try {
                val models = serverRepository.fetchModels(serverId)
                val globalModelId = preferredModelId
                    ?: settingsRepository.getLastActiveModelId().first().takeIf { it.isNotBlank() }
                val selectedModel = if (globalModelId != null && models.any { it.id == globalModelId }) {
                    globalModelId
                } else {
                    models.firstOrNull()?.id
                }
                _uiState.update {
                    it.copy(
                        availableModels = models,
                        selectedModelId = selectedModel,
                        isLoadingModels = false,
                    )
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Failed to load models (API)", e)
                _uiState.update { it.copy(error = e.message, isLoadingModels = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load models", e)
                _uiState.update { it.copy(error = "Failed to load models: ${e.message}", isLoadingModels = false) }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeConversation(conversationId: String) {
        observeGeneration(conversationId)
        observeImageReplies(conversationId)
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            conversationRepository.getById(conversationId)
                .map { conv ->
                    conv?.let { it.title to it.activeLeafMessageId }
                }
                .distinctUntilChanged()
                .flatMapLatest { pair ->
                    if (pair == null) return@flatMapLatest flowOf(emptyList<MessageEntity>())
                    val (title, leafId) = pair
                    _uiState.update { it.copy(conversationTitle = title) }
                    if (leafId != null) {
                        messageRepository.getActiveBranch(leafId)
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collectLatest { messages ->
                    _uiState.update {
                        it.copy(
                            messages = messages,
                            estimatedTokensUsed = TokenCounter.estimateTokens(messages),
                        )
                    }
                }
        }
        compactionJob?.cancel()
        compactionJob = viewModelScope.launch {
            compactionSummaryDao?.getByConversationId(conversationId)?.collect { summaries ->
                Log.d(TAG, "Compaction summaries updated: count=${summaries.size}, compactedCounts=${summaries.map { it.compactedMessageCount }}")
                _uiState.update { it.copy(compactionSummaries = summaries) }
            }
        }
    }

    /** Mirrors the conversation's in-flight reply (which may have started before this screen existed). */
    private fun observeGeneration(conversationId: String) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            launch {
                var wasGenerating = false
                generationManager.generation(conversationId).collect { gen ->
                    applyGeneration(gen, wasGenerating)
                    wasGenerating = gen != null
                }
            }
            launch {
                generationManager.error(conversationId).collect { error ->
                    if (error != null) {
                        _uiState.update { it.copy(error = error) }
                        generationManager.dismissError(conversationId)
                    }
                }
            }
        }
    }

    private fun applyGeneration(gen: ActiveGeneration?, wasGenerating: Boolean) {
        _uiState.update {
            when {
                gen != null -> it.copy(
                    isStreaming = true,
                    isStreamingLocal = gen.isLocal,
                    isCompacting = gen.isCompacting,
                    currentStreamingContent = gen.content,
                    currentStreamingThinking = gen.thinking,
                    streamStartedAtMs = gen.startedAtMs,
                    pendingToolCalls = gen.pendingToolCalls,
                    toolCallResults = gen.toolCallResults,
                )
                // Leave a manual compaction's flag alone unless a reply just finished
                else -> it.copy(
                    isStreaming = false,
                    isStreamingLocal = false,
                    isCompacting = if (wasGenerating) false else it.isCompacting,
                    currentStreamingContent = "",
                    currentStreamingThinking = "",
                    streamStartedAtMs = null,
                    pendingToolCalls = emptyList(),
                    toolCallResults = emptyMap(),
                )
            }
        }
    }

    /** Mirrors the conversation's image jobs and saved images, keyed by the assistant reply they belong to. */
    private fun observeImageReplies(conversationId: String) {
        val jobs = imageJobDao ?: return
        val images = generatedImageDao ?: return
        imageRepliesJob?.cancel()
        imageRepliesJob = viewModelScope.launch {
            combine(jobs.observeForConversation(conversationId), images.observeForConversation(conversationId)) { jobList, imageList ->
                val jobsByMessage = jobList.associateBy { it.messageId }
                val imagesByMessage = imageList.groupBy { it.messageId }
                (jobsByMessage.keys + imagesByMessage.keys).associateWith { id ->
                    ImageReplyState(jobsByMessage[id], imagesByMessage[id].orEmpty())
                }
            }.collect { replies -> _uiState.update { it.copy(imageReplies = replies) } }
        }
    }

    /** Tracks whether the selected server's models generate images (detection plus the user's overrides). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeImageModels() {
        val classifier = modelClassifier ?: return
        viewModelScope.launch {
            _uiState.map { state -> state.selectedServer?.takeUnless { state.useLocalModel } }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.baseUrl == b?.baseUrl }
                .flatMapLatest { server ->
                    if (server == null) {
                        flowOf<Pair<ImageServerInfo?, Map<String, Boolean>>>(null to emptyMap())
                    } else {
                        classifier.overrides(server.id).map { overrides -> classifier.serverInfo(server) to overrides }
                    }
                }
                .collect { (info, overrides) ->
                    _uiState.update {
                        it.copy(
                            imageServerDetected = info?.dialect == ImageDialect.SD_CPP,
                            imageCapabilities = info?.capabilities,
                            imageModelOverrides = overrides,
                        )
                    }
                }
        }
    }

    /** Marks a model of the selected server as generating images (true), chatting (false), or back to automatic (null). */
    fun setImageModelOverride(modelId: String, isImageModel: Boolean?) {
        val classifier = modelClassifier ?: return
        val server = _uiState.value.selectedServer ?: return
        viewModelScope.launch {
            val detected = _uiState.value.imageServerDetected
            // Choosing what detection would say anyway clears the override, so later detection changes apply
            classifier.setOverride(server.id, modelId, isImageModel?.takeIf { it != detected })
        }
    }

    /** Saves this conversation's image settings; a new chat keeps them until its first prompt creates it. */
    fun updateImageParams(params: ImageParameters) {
        _uiState.update { it.copy(imageParams = params) }
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch { conversationRepository.updateImageParams(conversationId, encodeImageParams(params)) }
    }

    /** Uses [seed] for the next images, so a liked image can be reproduced or varied. */
    fun reuseSeed(seed: Long) {
        updateImageParams(_uiState.value.imageParams.copy(seed = seed))
    }

    private fun decodeImageParams(json: String?): ImageParameters =
        json?.let { runCatching { imageJson.decodeFromString(ImageParameters.serializer(), it) }.getOrNull() } ?: ImageParameters()

    private fun encodeImageParams(params: ImageParameters): String? =
        params.takeIf { it != ImageParameters() }?.let { imageJson.encodeToString(ImageParameters.serializer(), it) }

    fun retryImage(jobId: String) {
        val launcher = imageJobLauncher ?: return
        viewModelScope.launch {
            try {
                launcher.retry(jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't retry image job", e)
                _uiState.update { it.copy(error = "Couldn't retry: ${e.message}") }
            }
        }
    }

    fun cancelImage(jobId: String) {
        val launcher = imageJobLauncher ?: return
        viewModelScope.launch { launcher.cancel(jobId) }
    }

    private suspend fun sendImagePrompt(conversationId: String, prompt: String, server: ServerProfileEntity, modelId: String) {
        val launcher = imageJobLauncher ?: return
        isFirstMessage = false
        try {
            launcher.generate(conversationId, prompt.trim(), server, modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start image generation", e)
            _uiState.update { it.copy(error = "Couldn't start image generation: ${e.message}") }
        }
    }

    private fun regenerateImage(message: MessageEntity, job: ImageJobEntity) {
        val launcher = imageJobLauncher ?: return
        viewModelScope.launch {
            try {
                // Regenerate with the model that made the image, even if the picker has moved on since
                val server = serverRepository.getById(job.serverId).first()
                    ?: throw IllegalStateException("The image server was removed")
                launcher.regenerate(message, server, job.modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't regenerate image", e)
                _uiState.update { it.copy(error = "Couldn't regenerate the image: ${e.message}") }
            }
        }
    }

    fun updateConversationParams(params: ConversationParameters) {
        _uiState.update { it.copy(conversationParams = params) }
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            conversationRepository.updateParameters(
                id = conversationId,
                serverProfileId = _uiState.value.selectedServer?.id,
                modelId = _uiState.value.selectedModelId,
                systemPrompt = params.systemPrompt,
                temperature = params.temperature,
                maxTokens = params.maxTokens,
                topP = params.topP,
                frequencyPenalty = params.frequencyPenalty,
                presencePenalty = params.presencePenalty,
            )
        }
    }

    fun resetConversationParamsToDefaults() {
        updateConversationParams(ConversationParameters())
    }

    fun toggleConversationSettings() {
        _uiState.update { it.copy(showConversationSettings = !it.showConversationSettings) }
    }

    fun dismissConversationSettings() {
        _uiState.update { it.copy(showConversationSettings = false) }
    }

    private fun loadTools() {
        viewModelScope.launch {
            toolDefinitionDao?.getAll()?.collect { tools ->
                _uiState.update { it.copy(availableTools = tools) }
            }
        }
    }

    private fun loadPresets() {
        viewModelScope.launch {
            parameterPresetDao?.getAll()?.collect { presets ->
                _uiState.update { it.copy(presets = presets) }
            }
        }
    }

    fun applyPreset(preset: ParameterPresetEntity) {
        val params = ConversationParameters(
            systemPrompt = _uiState.value.conversationParams.systemPrompt,
            temperature = preset.temperature,
            maxTokens = preset.maxTokens,
            topP = preset.topP,
            frequencyPenalty = preset.frequencyPenalty,
            presencePenalty = preset.presencePenalty,
        )
        updateConversationParams(params)
    }

    fun saveAsPreset(name: String) {
        val params = _uiState.value.conversationParams
        val defaults = _uiState.value.defaultParams
        viewModelScope.launch {
            parameterPresetDao?.insert(
                ParameterPresetEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    temperature = params.temperature ?: defaults.temperature,
                    maxTokens = params.maxTokens ?: defaults.maxTokens,
                    topP = params.topP ?: defaults.topP,
                    frequencyPenalty = params.frequencyPenalty ?: defaults.frequencyPenalty,
                    presencePenalty = params.presencePenalty ?: defaults.presencePenalty,
                )
            )
        }
    }

    fun approveToolCalls() {
        val conversationId = _uiState.value.conversationId ?: return
        generationManager.resolveToolCalls(conversationId, approved = true)
    }

    fun declineToolCalls() {
        val conversationId = _uiState.value.conversationId ?: return
        generationManager.resolveToolCalls(conversationId, approved = false)
    }

    fun toggleTool(toolId: String, enabled: Boolean) {
        val conversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            toolDefinitionDao?.setConversationToolEnabled(
                ConversationToolEnabledEntity(
                    conversationId = conversationId,
                    toolDefinitionId = toolId,
                    isEnabled = enabled,
                )
            )
        }
    }

    private fun resolvedParams(): ResolvedParams {
        val state = _uiState.value
        val conv = state.conversationParams
        val def = state.defaultParams
        return ResolvedParams(
            systemPrompt = conv.systemPrompt ?: def.systemPrompt,
            temperature = conv.temperature ?: def.temperature,
            maxTokens = conv.maxTokens ?: def.maxTokens,
            topP = conv.topP ?: def.topP,
            frequencyPenalty = conv.frequencyPenalty ?: def.frequencyPenalty,
            presencePenalty = conv.presencePenalty ?: def.presencePenalty,
        )
    }

    fun sendMessage(content: String) {
        sendMessageInternal(content)
    }

    fun sendMessageWithImages(content: String, imageUris: List<Uri>, context: Context) {
        if (_uiState.value.selectedModelIsImage) {
            _uiState.update { it.copy(error = "Editing images isn't supported yet. Send your prompt without attachments.") }
            return
        }
        val modelId = _uiState.value.selectedModelId
        if (modelId != null && !isLikelyVisionModel(modelId)) {
            _uiState.update { it.copy(error = "Warning: $modelId may not support images") }
        }
        val imageDataUrls = imageUris.mapNotNull { uri ->
            ImageCompressor.compressAndEncode(context, uri)
        }
        sendMessageInternal(content, imageDataUrls)
    }

    private fun isLikelyVisionModel(modelId: String): Boolean {
        // Local models: vision support is exactly "has a projector"
        _uiState.value.localModels.firstOrNull { it.id == modelId }?.let { return it.projectorFileName.isNotEmpty() }
        val lower = modelId.lowercase()
        return VISION_MODEL_KEYWORDS.any { lower.contains(it) }
    }

    private fun sendMessageInternal(content: String, imageDataUrls: List<String> = emptyList()) {
        val state = _uiState.value
        val isLocal = state.useLocalModel
        val serverId = if (isLocal) {
            LocalLlmClient.LOCAL_SERVER_ID
        } else {
            state.selectedServer?.id ?: run {
                _uiState.update { it.copy(error = "No server selected") }
                return
            }
        }
        val modelId = state.selectedModelId ?: run {
            _uiState.update { it.copy(error = "No model selected") }
            return
        }

        val resolved = resolvedParams()

        val titleRequest = if (isFirstMessage && !isLocal) TitleRequest(serverId, modelId, content) else null

        viewModelScope.launch {
            var conversationId = state.conversationId
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString()
                val title = content.take(50).let { if (content.length > 50) "$it..." else it }
                val now = System.currentTimeMillis()
                conversationRepository.create(
                    ConversationEntity(
                        id = conversationId,
                        title = title,
                        lastServerProfileId = if (isLocal) null else serverId,
                        lastModelId = modelId,
                        imageParamsJson = encodeImageParams(state.imageParams),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                _uiState.update { it.copy(conversationId = conversationId, conversationTitle = title) }
                settingsRepository.setLastActiveConversationId(conversationId)
                // Observe now so the user's message (and any failure) is visible before the reply completes
                observeConversation(conversationId)
            }

            val server = state.selectedServer
            if (!isLocal && server != null && modelClassifier?.isImageModel(server, modelId) == true) {
                _uiState.update { it.copy(error = null) }
                sendImagePrompt(conversationId, content, server, modelId)
                return@launch
            }

            val localContextWindowSize = if (isLocal) {
                localModelStore?.getById(modelId)?.contextWindowSize
            } else null

            _uiState.update { it.copy(error = null) }
            val refused = generationManager.start(conversationId, isLocal, titleRequest) {
                chatManager.sendMessage(
                    conversationId = conversationId,
                    content = content,
                    serverId = serverId,
                    modelId = modelId,
                    systemPrompt = resolved.systemPrompt,
                    temperature = resolved.temperature,
                    maxTokens = resolved.maxTokens,
                    topP = resolved.topP,
                    frequencyPenalty = resolved.frequencyPenalty,
                    presencePenalty = resolved.presencePenalty,
                    imageDataUrls = imageDataUrls,
                    contextWindowSize = localContextWindowSize,
                )
            }
            if (refused != null) {
                _uiState.update { it.copy(error = refused) }
            } else {
                isFirstMessage = false
            }
        }
    }

    fun stopGeneration() {
        val conversationId = _uiState.value.conversationId ?: return
        generationManager.stop(conversationId)
    }

    fun switchServer(serverId: String) {
        viewModelScope.launch {
            val server = serverRepository.getById(serverId).first() ?: return@launch
            _uiState.update { it.copy(selectedServer = server, availableModels = emptyList(), selectedModelId = null, isLoadingModels = true) }
            settingsRepository.setLastActiveServerId(serverId)
            try {
                val models = serverRepository.fetchModels(serverId)
                _uiState.update {
                    it.copy(availableModels = models, selectedModelId = models.firstOrNull()?.id, isLoadingModels = false)
                }
                persistServerAndModel()
            } catch (e: ApiException) {
                Log.e(TAG, "Failed to switch server (API)", e)
                _uiState.update { it.copy(error = e.message, isLoadingModels = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch server", e)
                _uiState.update { it.copy(error = "Failed to load models: ${e.message}", isLoadingModels = false) }
            }
        }
    }

    fun switchModel(modelId: String) {
        val localModels = _uiState.value.localModels
        if (localModels.any { it.id == modelId }) {
            switchToLocal(modelId)
        } else {
            _uiState.update { it.copy(selectedModelId = modelId) }
            persistServerAndModel()
            viewModelScope.launch {
                settingsRepository.setLastActiveModelId(modelId)
            }
        }
    }

    private fun persistServerAndModel() {
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        val serverId = if (state.useLocalModel) null else state.selectedServer?.id
        val modelId = state.selectedModelId
        viewModelScope.launch {
            conversationRepository.updateServerAndModel(conversationId, serverId, modelId)
        }
    }

    fun regenerateMessage(message: MessageEntity) {
        _uiState.value.imageReplies[message.id]?.job?.let { job ->
            regenerateImage(message, job)
            return
        }
        val parentId = message.parentMessageId ?: return
        val state = _uiState.value
        val isLocal = state.useLocalModel
        val serverId = if (isLocal) LocalLlmClient.LOCAL_SERVER_ID else (state.selectedServer?.id ?: return)
        val modelId = state.selectedModelId ?: return
        val resolved = resolvedParams()

        viewModelScope.launch {
            val localContextWindowSize = if (isLocal) {
                localModelStore?.getById(modelId)?.contextWindowSize
            } else null

            _uiState.update { it.copy(error = null) }
            val refused = generationManager.start(message.conversationId, isLocal) {
                flow {
                    // Set active leaf to the parent so ChatManager builds from there
                    conversationRepository.updateActiveLeaf(message.conversationId, parentId)
                    emitAll(
                        chatManager.sendMessage(
                            conversationId = message.conversationId,
                            content = "", // Empty - we're regenerating from the parent's user message
                            serverId = serverId,
                            modelId = modelId,
                            systemPrompt = resolved.systemPrompt,
                            temperature = resolved.temperature,
                            maxTokens = resolved.maxTokens,
                            topP = resolved.topP,
                            frequencyPenalty = resolved.frequencyPenalty,
                            presencePenalty = resolved.presencePenalty,
                            contextWindowSize = localContextWindowSize,
                        )
                    )
                }
            }
            if (refused != null) _uiState.update { it.copy(error = refused) }
        }
    }

    fun startEditMessage(message: MessageEntity) {
        _uiState.update { it.copy(editingMessage = message) }
    }

    fun cancelEditMessage() {
        _uiState.update { it.copy(editingMessage = null) }
    }

    fun confirmEditMessage(newContent: String) {
        val message = _uiState.value.editingMessage ?: return
        _uiState.update { it.copy(editingMessage = null) }

        viewModelScope.launch {
            messageRepository.updateContent(message.id, newContent)
            messageRepository.deleteMessagesAfterDepth(message.conversationId, message.depth)
            conversationRepository.updateActiveLeaf(message.conversationId, message.id)
            observeConversation(message.conversationId)
        }
    }

    fun deleteMessage(message: MessageEntity) {
        viewModelScope.launch {
            val conversationId = message.conversationId
            val parentId = message.parentMessageId
            // Stop a pending image job first so its worker doesn't keep the server busy for a deleted reply
            _uiState.value.imageReplies[message.id]?.job?.takeUnless { it.status.isTerminal }?.let { job ->
                imageJobLauncher?.cancel(job.id)
            }

            messageRepository.deleteMessage(message.id)

            // If this was the active branch, navigate to parent or sibling
            val conversation = conversationRepository.getById(conversationId).first()
            if (conversation?.activeLeafMessageId == message.id || conversation?.activeLeafMessageId == null) {
                conversationRepository.updateActiveLeaf(conversationId, parentId)
                observeConversation(conversationId)
            }
        }
    }

    suspend fun exportConversation(): String? {
        val conversationId = _uiState.value.conversationId ?: return null
        val replies = _uiState.value.imageReplies
        return conversationRepository.exportAsMarkdown(conversationId, messageRepository) { messages ->
            describeImageReplies(
                messages,
                replies.mapNotNull { (id, reply) -> reply.job?.let { id to it } }.toMap(),
                replies.mapValues { it.value.images },
            )
        }
    }

    fun exportConversationToFile(uri: Uri, context: Context) {
        viewModelScope.launch {
            val markdown = exportConversation() ?: return@launch
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(markdown.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file", e)
                _uiState.update { it.copy(error = "Failed to save file: ${e.message}") }
            }
        }
    }

    fun compactConversation() {
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        val serverId = if (state.useLocalModel) {
            LocalLlmClient.LOCAL_SERVER_ID
        } else {
            state.selectedServer?.id ?: return
        }
        val modelId = state.selectedModelId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isCompacting = true) }
            val localContextWindowSize = if (state.useLocalModel) {
                localModelStore?.getById(modelId)?.contextWindowSize
            } else null
            Log.d(TAG, "Starting compaction for conversation=${conversationId.take(8)}, model=$modelId")
            val summary = chatManager.compactConversation(conversationId, serverId, modelId, localContextWindowSize)
            Log.d(TAG, "Compaction result: ${if (summary != null) "success (${summary.length} chars)" else "null"}")
            Log.d(TAG, "Current summaries in state: ${_uiState.value.compactionSummaries.size}")
            _uiState.update { it.copy(isCompacting = false) }
            if (summary != null) {
                _uiState.update { it.copy(error = null) }
            } else {
                _uiState.update { it.copy(error = "Not enough messages to compact") }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

}

private data class ResolvedParams(
    val systemPrompt: String?,
    val temperature: Float?,
    val maxTokens: Int?,
    val topP: Float?,
    val frequencyPenalty: Float?,
    val presencePenalty: Float?,
)
