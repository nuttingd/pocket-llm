package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompactionSummaryDao {

    @Query("SELECT * FROM compaction_summaries WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getByConversationId(conversationId: String): Flow<List<CompactionSummaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: CompactionSummaryEntity)

    @Query("SELECT MAX(compactedMessageCount) FROM compaction_summaries WHERE conversationId = :conversationId")
    suspend fun getMaxCompactedCount(conversationId: String): Int?

    @Query("DELETE FROM compaction_summaries WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)
}
