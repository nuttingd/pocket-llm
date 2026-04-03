package dev.nutting.pocketllm.data.local.dao

import androidx.room.*
import dev.nutting.pocketllm.data.local.entity.DocumentContentEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for DocumentContentEntity with CRUD operations.
 */
@Dao
interface DocumentContentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(content: DocumentContentEntity): Long

    @Update
    suspend fun update(content: DocumentContentEntity)

    @Delete
    suspend fun delete(content: DocumentContentEntity)

    @Query("SELECT * FROM document_content WHERE document_id = :documentId")
    suspend fun getByDocumentId(documentId: String): DocumentContentEntity?

    /**
     * Get all document contents.
     */
    @Query("SELECT * FROM document_content")
    fun getAll(): Flow<List<DocumentContentEntity>>
}
