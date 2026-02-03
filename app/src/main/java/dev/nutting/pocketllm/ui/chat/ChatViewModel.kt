package dev.nutting.pocketllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ChatManager
import dev.nutting.pocketllm.domain.StreamState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val chatManager: ChatManager,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var messagesJob: Job? = null

    fun loadConversation(conversationId: String?) {
        if (conversationId != null) {
            _uiState.update { it.copy(conversationId = conversationId) }
            observeConversation(conversationId)
        }
        loadServerAndModels()
    }

    private fun loadServerAndModels() {
        viewModelScope.launch {
            val serverId = settingsRepository.getLastActiveServerId().first()
            if (serverId.isBlank()) return@launch
            val server = serverRepository.getById(serverId).first() ?: return@launch
            _uiState.update { it.copy(selectedServer = server) }

            try {
                val models = serverRepository.fetchModels(serverId)
                _uiState.update {
                    it.copy(
                        availableModels = models,
                        selectedModelId = it.selectedModelId ?: models.firstOrNull()?.id,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
        }
    }

    private fun observeConversation(conversationId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first()
            if (conversation != null) {
                _uiState.update { it.copy(conversationTitle = conversation.title) }
                val leafId = conversation.activeLeafMessageId
                if (leafId != null) {
                    messageRepository.getActiveBranch(leafId).collect { messages ->
                        _uiState.update { it.copy(messages = messages) }
                    }
                }
            }
        }
    }

    fun sendMessage(content: String) {
        val state = _uiState.value
        val server = state.selectedServer ?: run {
            _uiState.update { it.copy(error = "No server selected") }
            return
        }
        val modelId = state.selectedModelId ?: run {
            _uiState.update { it.copy(error = "No model selected") }
            return
        }

        streamJob = viewModelScope.launch {
            // Create conversation if needed
            var conversationId = state.conversationId
            if (conversationId == null) {
                conversationId = UUID.randomUUID().toString()
                val title = content.take(50).let { if (content.length > 50) "$it..." else it }
                val now = System.currentTimeMillis()
                conversationRepository.create(
                    ConversationEntity(
                        id = conversationId,
                        title = title,
                        lastServerProfileId = server.id,
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

            chatManager.sendMessage(
                conversationId = conversationId,
                content = content,
                serverId = server.id,
                modelId = modelId,
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
                        observeConversation(conversationId)
                    }
                    is StreamState.Error -> {
                        _uiState.update {
                            it.copy(isStreaming = false, error = streamState.error, currentStreamingContent = "", currentStreamingThinking = "")
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
            _uiState.update { it.copy(selectedServer = server, availableModels = emptyList(), selectedModelId = null) }
            settingsRepository.setLastActiveServerId(serverId)
            try {
                val models = serverRepository.fetchModels(serverId)
                _uiState.update {
                    it.copy(availableModels = models, selectedModelId = models.firstOrNull()?.id)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
        }
    }

    fun switchModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
