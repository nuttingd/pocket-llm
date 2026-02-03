package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.SearchResult
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE parentMessageId = :parentId ORDER BY createdAt ASC")
    fun getChildMessages(parentId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND parentMessageId IS NULL ORDER BY createdAt ASC")
    fun getConversationRootMessages(conversationId: String): Flow<List<MessageEntity>>

    /**
     * Walk from a leaf message up to the root by following parentMessageId.
     * Returns the active branch in root-to-leaf order.
     */
    @Query(
        """
        WITH RECURSIVE branch(id, conversationId, parentMessageId, role, content, thinkingContent,
            serverProfileId, modelId, promptTokens, completionTokens, totalTokens,
            toolCallId, toolCallsJson, imageUris, depth, childCount, createdAt) AS (
            SELECT * FROM messages WHERE id = :leafId
            UNION ALL
            SELECT m.* FROM messages m INNER JOIN branch b ON m.id = b.parentMessageId
        )
        SELECT * FROM branch ORDER BY depth ASC
        """
    )
    fun getActiveBranch(leafId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET childCount = childCount + 1 WHERE id = :parentId")
    suspend fun incrementChildCount(parentId: String)

    @Query("UPDATE messages SET childCount = childCount - 1 WHERE id = :parentId")
    suspend fun decrementChildCount(parentId: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getAllByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.id AS messageId, m.conversationId, c.title AS conversationTitle,
               m.role, SUBSTR(m.content, 1, 120) AS snippet, m.createdAt
        FROM message_fts fts
        JOIN messages m ON m.rowid = fts.rowid
        JOIN conversations c ON c.id = m.conversationId
        WHERE message_fts MATCH :query
        ORDER BY m.createdAt DESC
        LIMIT 50
        """
    )
    fun searchMessages(query: String): Flow<List<SearchResult>>

    @Query(
        """
        SELECT m.id AS messageId, m.conversationId, c.title AS conversationTitle,
               m.role, SUBSTR(m.content, 1, 120) AS snippet, m.createdAt
        FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE c.title LIKE '%' || :query || '%'
        GROUP BY m.conversationId
        ORDER BY m.createdAt DESC
        LIMIT 50
        """
    )
    fun searchConversationTitles(query: String): Flow<List<SearchResult>>
}
