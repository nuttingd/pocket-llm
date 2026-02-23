package dev.nutting.pocketllm.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.remote.ApiException
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.dao.ParameterPresetDao
import dev.nutting.pocketllm.data.local.dao.ToolDefinitionDao
import android.content.Context
import android.net.Uri
import dev.nutting.pocketllm.data.local.entity.ConversationToolEnabledEntity
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.util.ImageCompressor
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ChatManager
import dev.nutting.pocketllm.domain.LocalLlmClient
import dev.nutting.pocketllm.domain.StreamState
import dev.nutting.pocketllm.util.TokenCounter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val chatManager: ChatManager,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val localModelStore: LocalModelStore? = null,
    private val toolDefinitionDao: ToolDefinitionDao? = null,
    private val parameterPresetDao: ParameterPresetDao? = null,
    private val compactionSummaryDao: CompactionSummaryDao? = null,
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

    private var streamJob: Job? = null
    private var messagesJob: Job? = null
    private var compactionJob: Job? = null
    private var isFirstMessage = true
    private var toolApprovalDeferred: CompletableDeferred<Boolean>? = null

    init {
        loadServers()
        loadDefaults()
        loadTools()
        loadPresets()
        observeFontSize()
        observeLocalModels()
        chatManager.toolApprovalCallback = { toolCalls ->
            val deferred = CompletableDeferred<Boolean>()
            toolApprovalDeferred = deferred
            _uiState.update { it.copy(pendingToolCalls = toolCalls, toolCallResults = emptyMap()) }
            deferred.await()
        }
    }

    fun loadConversation(conversationId: String?) {
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
            _uiState.update { it.copy(conversationParams = ConversationParameters()) }
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
                    )
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
                val selectedModel = if (preferredModelId != null && models.any { it.id == preferredModelId }) {
                    preferredModelId
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
        _uiState.update { it.copy(pendingToolCalls = emptyList()) }
        toolApprovalDeferred?.complete(true)
        toolApprovalDeferred = null
    }

    fun declineToolCalls() {
        _uiState.update { it.copy(pendingToolCalls = emptyList()) }
        toolApprovalDeferred?.complete(false)
        toolApprovalDeferred = null
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

        streamJob = viewModelScope.launch {
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
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                _uiState.update { it.copy(conversationId = conversationId, conversationTitle = title) }
                settingsRepository.setLastActiveConversationId(conversationId)
            }

            _uiState.update {
                it.copy(
                    isStreaming = true,
                    currentStreamingContent = "",
                    currentStreamingThinking = "",
                    error = null,
                )
            }

            val localContextWindowSize = if (isLocal) {
                localModelStore?.getById(modelId)?.contextWindowSize
            } else null

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
            ).collect { streamState ->
                when (streamState) {
                    is StreamState.Delta -> {
                        _uiState.update {
                            it.copy(
                                currentStreamingContent = it.currentStreamingContent + streamState.content,
                                currentStreamingThinking = it.currentStreamingThinking + (streamState.thinkingContent ?: ""),
                            )
                        }
                    }
                    is StreamState.Complete -> {
                        _uiState.update {
                            it.copy(isStreaming = false, currentStreamingContent = "", currentStreamingThinking = "", toolCallResults = emptyMap())
                        }
                        observeConversation(conversationId)
                        if (isFirstMessage) {
                            isFirstMessage = false
                            generateTitle(conversationId, content, streamState.message.content)
                        }
                    }
                    is StreamState.Error -> {
                        Log.e(TAG, "Stream error: ${streamState.error}")
                        _uiState.update {
                            it.copy(isStreaming = false, error = streamState.error, currentStreamingContent = "", currentStreamingThinking = "")
                        }
                    }
                    is StreamState.ToolCallsPending -> {
                        _uiState.update { it.copy(currentStreamingContent = "") }
                    }
                    is StreamState.ToolCallResult -> {
                        _uiState.update {
                            it.copy(
                                toolCallResults = it.toolCallResults + (streamState.toolCallId to streamState.result),
                                currentStreamingContent = "",
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        chatManager.stopGeneration()
        _uiState.update { it.copy(isStreaming = false) }
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
        val parentId = message.parentMessageId ?: return
        val state = _uiState.value
        val isLocal = state.useLocalModel
        val serverId = if (isLocal) LocalLlmClient.LOCAL_SERVER_ID else (state.selectedServer?.id ?: return)
        val modelId = state.selectedModelId ?: return
        val resolved = resolvedParams()

        streamJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isStreaming = true, currentStreamingContent = "", currentStreamingThinking = "", error = null)
            }

            // Set active leaf to the parent so ChatManager builds from there
            conversationRepository.updateActiveLeaf(message.conversationId, parentId)

            val localContextWindowSize = if (isLocal) {
                localModelStore?.getById(modelId)?.contextWindowSize
            } else null

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
            ).collect { streamState ->
                when (streamState) {
                    is StreamState.Delta -> {
                        _uiState.update {
                            it.copy(
                                currentStreamingContent = it.currentStreamingContent + streamState.content,
                                currentStreamingThinking = it.currentStreamingThinking + (streamState.thinkingContent ?: ""),
                            )
                        }
                    }
                    is StreamState.Complete -> {
                        _uiState.update {
                            it.copy(isStreaming = false, currentStreamingContent = "", currentStreamingThinking = "")
                        }
                        observeConversation(message.conversationId)
                    }
                    is StreamState.Error -> {
                        Log.e(TAG, "Regeneration stream error: ${streamState.error}")
                        _uiState.update {
                            it.copy(isStreaming = false, error = streamState.error, currentStreamingContent = "", currentStreamingThinking = "")
                        }
                    }
                    is StreamState.ToolCallsPending -> {
                        _uiState.update { it.copy(currentStreamingContent = "") }
                    }
                    is StreamState.ToolCallResult -> {
                        _uiState.update {
                            it.copy(toolCallResults = it.toolCallResults + (streamState.toolCallId to streamState.result))
                        }
                    }
                }
            }
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
        return conversationRepository.exportAsMarkdown(conversationId, messageRepository)
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
            Log.d(TAG, "Starting compaction for conversation=$conversationId, server=$serverId, model=$modelId")
            val summary = chatManager.compactConversation(conversationId, serverId, modelId)
            Log.d(TAG, "Compaction result: ${if (summary != null) "success (${summary.take(50)}...)" else "null"}")
            Log.d(TAG, "Current summaries in state: ${_uiState.value.compactionSummaries.size}")
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

    private fun generateTitle(conversationId: String, userMessage: String, assistantMessage: String) {
        viewModelScope.launch {
            val state = _uiState.value

            // For local models, use simple title from first message
            if (state.useLocalModel) {
                val title = userMessage.take(50).let { if (userMessage.length > 50) "$it..." else it }
                conversationRepository.rename(conversationId, title)
                _uiState.update { it.copy(conversationTitle = title) }
                return@launch
            }

            val server = state.selectedServer ?: return@launch
            val modelId = state.selectedModelId ?: return@launch
            val apiKey = if (server.hasApiKey) {
                serverRepository.getApiKey(server.id).first()
            } else null

            try {
                val request = dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest(
                    model = modelId,
                    messages = listOf(
                        dev.nutting.pocketllm.data.remote.model.ChatMessage(
                            role = "system",
                            content = dev.nutting.pocketllm.data.remote.model.ChatContent.Text(
                                "Generate a short title (max 6 words) for this conversation. Respond with only the title, no quotes or punctuation."
                            ),
                        ),
                        dev.nutting.pocketllm.data.remote.model.ChatMessage(
                            role = "user",
                            content = dev.nutting.pocketllm.data.remote.model.ChatContent.Text(userMessage),
                        ),
                        dev.nutting.pocketllm.data.remote.model.ChatMessage(
                            role = "assistant",
                            content = dev.nutting.pocketllm.data.remote.model.ChatContent.Text(assistantMessage.take(200)),
                        ),
                    ),
                    maxTokens = 20,
                    temperature = 0.3f,
                    stream = false,
                )

                val apiClient = dev.nutting.pocketllm.data.remote.OpenAiApiClient()
                val response = apiClient.chatCompletion(
                    baseUrl = server.baseUrl,
                    apiKey = apiKey,
                    timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                    request = request,
                )
                val title = response.choices.firstOrNull()?.message?.content?.trim()
                    ?.take(60) ?: return@launch

                conversationRepository.rename(conversationId, title)
                _uiState.update { it.copy(conversationTitle = title) }
            } catch (e: Exception) {
                Log.e(TAG, "Title generation failed", e)
            }
        }
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
