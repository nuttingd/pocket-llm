package dev.nutting.pocketllm.data.local.dao

import androidx.room.*
import dev.nutting.pocketllm.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for DocumentEntity with CRUD operations and FTS search.
 *
 * Search uses SQLite FTS4 through the document_fts virtual table.
 */
@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    fun getAllSorted(): Flow<List<DocumentEntity>>

    /**
     * Search documents using FTS4 MATCH operator.
     */
    @Query("""
        SELECT d.id, d.title, d.source_filename as sourceFileName, d.word_count as wordCount, d.created_at as createdAt
        FROM documents d
        WHERE d.id IN (
            SELECT docid FROM document_fts 
            WHERE content MATCH :query 
            LIMIT 50
        )
        ORDER BY d.updated_at DESC
    """)
    fun searchDocuments(query: String): Flow<List<DocumentSearchResult>>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getCount(): Int

    @Query("SELECT SUM(size_bytes) FROM documents")
    suspend fun getTotalSizeBytes(): Long?

    /**
     * Search by title or filename using LIKE (for non-FTS searches).
     */
    @Query("""
        SELECT d.id, d.title, d.source_filename as sourceFileName, d.word_count as wordCount, d.created_at as createdAt
        FROM documents d
        WHERE d.title LIKE '%' || :query || '%' 
           OR d.source_filename LIKE '%' || :query || '%'
        ORDER BY d.updated_at DESC
        LIMIT 50
    """)
    fun searchByTitleOrFilename(query: String): Flow<List<DocumentSearchResult>>
}

/**
 * Data class representing search result from DocumentDao.searchDocuments().
 *
 * This is not a Room entity - it's a query projection for UI display.
 */
data class DocumentSearchResult(
    val id: String,
    val title: String,
    val sourceFileName: String,
    val wordCount: Int,
    val createdAt: Long,
)
