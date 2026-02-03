package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllByUpdatedAt(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET activeLeafMessageId = :leafId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateActiveLeaf(id: String, leafId: String?, updatedAt: Long)

    @Query(
        """UPDATE conversations SET
            lastServerProfileId = :serverProfileId,
            lastModelId = :modelId,
            systemPrompt = :systemPrompt,
            temperature = :temperature,
            maxTokens = :maxTokens,
            topP = :topP,
            frequencyPenalty = :frequencyPenalty,
            presencePenalty = :presencePenalty,
            updatedAt = :updatedAt
        WHERE id = :id"""
    )
    suspend fun updateParameters(
        id: String,
        serverProfileId: String?,
        modelId: String?,
        systemPrompt: String?,
        temperature: Float?,
        maxTokens: Int?,
        topP: Float?,
        frequencyPenalty: Float?,
        presencePenalty: Float?,
        updatedAt: Long,
    )

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}
