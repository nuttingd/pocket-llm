package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ImageJobEntity)

    @Update
    suspend fun update(job: ImageJobEntity)

    @Query("SELECT * FROM image_jobs WHERE id = :id")
    suspend fun getById(id: String): ImageJobEntity?

    @Query("SELECT * FROM image_jobs WHERE conversationId = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<ImageJobEntity>>

    @Query("SELECT * FROM image_jobs WHERE messageId IN (:messageIds)")
    suspend fun getForMessages(messageIds: List<String>): List<ImageJobEntity>

    /** Jobs that haven't reached a terminal state, e.g. to re-enqueue their work after an app update. */
    @Query("SELECT * FROM image_jobs WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun getActive(): List<ImageJobEntity>
}
