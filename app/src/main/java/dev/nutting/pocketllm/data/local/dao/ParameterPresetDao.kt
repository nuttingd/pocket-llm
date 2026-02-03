package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ParameterPresetDao {

    @Query("SELECT * FROM parameter_presets ORDER BY isBuiltIn DESC, name ASC")
    fun getAll(): Flow<List<ParameterPresetEntity>>

    @Query("SELECT * FROM parameter_presets WHERE id = :id")
    suspend fun getById(id: String): ParameterPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: ParameterPresetEntity)

    @Query("DELETE FROM parameter_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: String)
}
