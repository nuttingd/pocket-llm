package dev.nutting.pocketllm.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Main record for each uploaded document, storing metadata and statistics.
 *
 * @property id UUID v4 identifier (primary key)
 * @property title Display title (from filename or extracted)
 * @property sourceFileName Original uploaded filename
 * @property mimeType MIME type (application/pdf, text/plain, etc.)
 * @property wordCount Word count for statistics
 * @property sizeBytes File size in bytes
 * @property createdAt Unix timestamp of upload
 * @property updatedAt Unix timestamp of last modification
 */
@Entity(
    tableName = "documents"
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "title", index = true) val title: String,
    @ColumnInfo(name = "source_filename") val sourceFileName: String,
    @ColumnInfo(name = "mimeType") val mimeType: String,
    @ColumnInfo(defaultValue = "0", name = "word_count") val wordCount: Int,
    @ColumnInfo(defaultValue = "0", name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
