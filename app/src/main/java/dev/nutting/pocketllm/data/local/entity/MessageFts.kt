package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "message_fts")
data class MessageFts(
    val content: String,
)
