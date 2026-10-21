package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.GeneratedImageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedImageDao {

    /** Upsert by (messageId, idx) so saving a job's results is idempotent. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(images: List<GeneratedImageEntity>)

    @Query(
        """SELECT gi.* FROM generated_images gi
           JOIN messages m ON m.id = gi.messageId
           WHERE m.conversationId = :conversationId
           ORDER BY gi.messageId, gi.idx"""
    )
    fun observeForConversation(conversationId: String): Flow<List<GeneratedImageEntity>>

    @Query("SELECT * FROM generated_images WHERE messageId IN (:messageIds) ORDER BY messageId, idx")
    suspend fun getForMessages(messageIds: List<String>): List<GeneratedImageEntity>

    /** Every stored path, for the orphan sweep. */
    @Query("SELECT filePath FROM generated_images")
    suspend fun getAllFilePaths(): List<String>
}
