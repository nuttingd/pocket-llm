package dev.nutting.pocketllm.data.local.entity

data class SearchResult(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val snippet: String,
    val createdAt: Long,
)
