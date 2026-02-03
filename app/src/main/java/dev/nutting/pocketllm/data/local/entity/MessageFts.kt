package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "message_fts")
data class MessageFts(
    val content: String,
)
