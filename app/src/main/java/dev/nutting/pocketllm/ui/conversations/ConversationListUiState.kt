package dev.nutting.pocketllm.ui.conversations

import dev.nutting.pocketllm.data.local.entity.SearchResult

data class ConversationListUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val isSearchActive: Boolean = false,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val lastMessagePreview: String? = null,
    val updatedAt: Long,
)
