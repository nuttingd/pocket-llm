package dev.nutting.pocketllm.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Stores the extracted text content of each document for FTS indexing.
 *
 * @property documentId Reference to parent document (primary key, foreign key)
 * @property content Full-text content extracted from document
 */
@Entity(
    tableName = "document_content",
    primaryKeys = ["document_id"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentContentEntity(
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "content") val content: String,
)
