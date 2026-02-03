package dev.nutting.pocketllm.ui.conversations

data class ConversationListUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = false,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val lastMessagePreview: String? = null,
    val updatedAt: Long,
)
