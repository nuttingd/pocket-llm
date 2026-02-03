package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "compaction_summaries",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
    ],
)
data class CompactionSummaryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val summary: String,
    val compactedMessageCount: Int,
    val insertedBeforeMessageId: String? = null,
    val createdAt: Long,
)
