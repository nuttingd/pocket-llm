package dev.nutting.pocketllm.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["lastServerProfileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("updatedAt"),
        Index("lastServerProfileId"),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "NULL") val lastServerProfileId: String? = null,
    val lastModelId: String? = null,
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val activeLeafMessageId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
