package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "conversation_tool_enabled",
    primaryKeys = ["conversationId", "toolDefinitionId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ToolDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolDefinitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index("toolDefinitionId"),
    ],
)
data class ConversationToolEnabledEntity(
    val conversationId: String,
    val toolDefinitionId: String,
    val isEnabled: Boolean = true,
)
