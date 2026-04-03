package dev.nutting.pocketllm.data.local.dao

import androidx.room.*
import dev.nutting.pocketllm.data.local.entity.ConversationLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for ConversationLinkEntity with CRUD operations.
 */
@Dao
interface ConversationLinkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(link: ConversationLinkEntity): Long

    @Delete
    suspend fun delete(link: ConversationLinkEntity)

    /**
     * Get all documents linked to a conversation.
     */
    @Query("SELECT documentId FROM conversation_links WHERE conversationId = :conversationId")
    fun getLinkedDocumentIds(conversationId: String): Flow<List<String>>

    /**
     * Check if a document is already linked to a conversation.
     */
    @Query("SELECT COUNT(*) FROM conversation_links WHERE conversationId = :conversationId AND documentId = :documentId")
    suspend fun isLinked(conversationId: String, documentId: String): Boolean

    @Query("SELECT * FROM conversation_links WHERE conversationId = :conversationId")
    fun getLinksByConversation(conversationId: String): Flow<List<ConversationLinkEntity>>

    /**
     * Get all links for a specific document.
     */
    @Query("SELECT * FROM conversation_links WHERE documentId = :documentId")
    fun getLinksForDocument(documentId: String): Flow<List<ConversationLinkEntity>>

    /**
     * Get link by conversation and document ID.
     */
    @Query("SELECT * FROM conversation_links WHERE conversationId = :conversationId AND documentId = :documentId LIMIT 1")
    suspend fun getLink(conversationId: String, documentId: String): ConversationLinkEntity?

    /**
     * Count linked documents per conversation.
     */
    @Query("SELECT COUNT(*) FROM conversation_links WHERE conversationId = :conversationId")
    suspend fun countLinkedDocuments(conversationId: String): Int
}
