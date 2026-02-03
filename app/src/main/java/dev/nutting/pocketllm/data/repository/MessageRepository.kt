package dev.nutting.pocketllm.data.repository

import dev.nutting.pocketllm.data.local.dao.MessageDao
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class MessageRepository(
    private val dao: MessageDao,
) {
    suspend fun insertMessage(message: MessageEntity) {
        dao.insert(message)
        message.parentMessageId?.let { dao.incrementChildCount(it) }
    }

    fun getActiveBranch(leafId: String): Flow<List<MessageEntity>> =
        dao.getActiveBranch(leafId)

    fun getChildren(parentId: String): Flow<List<MessageEntity>> =
        dao.getChildMessages(parentId)

    fun getConversationRootMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.getConversationRootMessages(conversationId)

    suspend fun updateContent(id: String, content: String) {
        dao.updateContent(id, content)
    }

    suspend fun deleteMessagesAfterDepth(conversationId: String, depth: Int) {
        dao.deleteMessagesAfterDepth(conversationId, depth)
    }

    suspend fun deleteMessage(id: String) {
        val message = dao.getById(id) ?: return
        message.parentMessageId?.let { dao.decrementChildCount(it) }
        dao.deleteById(id)
    }

    suspend fun getById(id: String): MessageEntity? = dao.getById(id)

    fun getAllByConversation(conversationId: String): Flow<List<MessageEntity>> =
        dao.getAllByConversation(conversationId)

    fun search(query: String): Flow<List<SearchResult>> {
        val ftsQuery = query.trim().split("\\s+".toRegex()).joinToString(" ") { "$it*" }
        return combine(
            dao.searchMessages(ftsQuery),
            dao.searchConversationTitles(query.trim()),
        ) { messageResults, titleResults ->
            (titleResults + messageResults)
                .distinctBy { "${it.conversationId}:${it.messageId}" }
                .sortedByDescending { it.createdAt }
                .take(50)
        }
    }
}
