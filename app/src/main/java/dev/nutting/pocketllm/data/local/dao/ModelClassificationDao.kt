package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.ModelClassificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelClassificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(classification: ModelClassificationEntity)

    @Query("DELETE FROM model_classifications WHERE serverId = :serverId AND modelId = :modelId")
    suspend fun delete(serverId: String, modelId: String)

    @Query("SELECT * FROM model_classifications WHERE serverId = :serverId")
    fun observeForServer(serverId: String): Flow<List<ModelClassificationEntity>>
}
