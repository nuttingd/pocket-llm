package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

/**
 * SQLite FTS4 virtual table for full-text search indexing.
 */
@Fts4
@Entity(tableName = "document_fts")
data class DocumentFts(
    val content: String,
)
