package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.ConversationToolEnabledEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolDefinitionDao {

    @Query("SELECT * FROM tool_definitions ORDER BY name ASC")
    fun getAll(): Flow<List<ToolDefinitionEntity>>

    @Query("SELECT * FROM tool_definitions WHERE id = :id")
    suspend fun getById(id: String): ToolDefinitionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tool: ToolDefinitionEntity)

    @Query("DELETE FROM tool_definitions WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String)

    @Query(
        """
        SELECT td.* FROM tool_definitions td
        LEFT JOIN conversation_tool_enabled cte
            ON td.id = cte.toolDefinitionId AND cte.conversationId = :conversationId
        WHERE COALESCE(cte.isEnabled, td.isEnabledByDefault) = 1
        """
    )
    fun getEnabledForConversation(conversationId: String): Flow<List<ToolDefinitionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConversationToolEnabled(entry: ConversationToolEnabledEntity)

    @Query("SELECT * FROM conversation_tool_enabled WHERE conversationId = :conversationId")
    fun getConversationToolOverrides(conversationId: String): Flow<List<ConversationToolEnabledEntity>>
}
