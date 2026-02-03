package dev.nutting.pocketllm.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ConversationListViewModel(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            conversationRepository.getAllSorted().collect { conversations ->
                val summaries = conversations.map { conv ->
                    val preview = conv.activeLeafMessageId?.let { leafId ->
                        val branch = messageRepository.getActiveBranch(leafId).first()
                        branch.lastOrNull()?.content?.take(80)
                    }
                    ConversationSummary(
                        id = conv.id,
                        title = conv.title,
                        lastMessagePreview = preview,
                        updatedAt = conv.updatedAt,
                    )
                }
                _uiState.update { it.copy(conversations = summaries, isLoading = false) }
            }
        }
    }

    fun createNewConversation(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            conversationRepository.create(
                ConversationEntity(
                    id = id,
                    title = "New Chat",
                    createdAt = now,
                    updatedAt = now,
                )
            )
            settingsRepository.setLastActiveConversationId(id)
        }
        return id
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationRepository.rename(id, newTitle)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.delete(id)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            messageRepository.search(query).collect { results ->
                _uiState.update { it.copy(searchResults = results) }
            }
        }
    }

    fun toggleSearch() {
        val active = !_uiState.value.isSearchActive
        _uiState.update {
            it.copy(
                isSearchActive = active,
                searchQuery = if (active) it.searchQuery else "",
                searchResults = if (active) it.searchResults else emptyList(),
            )
        }
        if (!active) searchJob?.cancel()
    }
}
