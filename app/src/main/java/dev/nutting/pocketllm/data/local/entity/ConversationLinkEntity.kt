package dev.nutting.pocketllm.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Links documents to conversations for context-aware AI responses.
 *
 * @property id Auto-incrementing unique identifier
 * @property conversationId Reference to conversation
 * @property documentId Reference to document
 * @property linkedAt Unix timestamp of link creation
 */
@Entity(
    tableName = "conversation_links",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index("documentId"),
    ],
)
data class ConversationLinkEntity(
    @ColumnInfo(name = "conversationId") val conversationId: String,
    @ColumnInfo(name = "documentId") val documentId: String,
    @ColumnInfo(name = "linked_at") val linkedAt: Long,
)
