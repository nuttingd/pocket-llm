package dev.nutting.pocketllm.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus
import kotlinx.coroutines.flow.Flow

/**
 * State changes are conditional on the job not being finished, so a background write (progress, a late
 * result) can never overwrite a cancel the user made in the meantime. Each returns the rows changed:
 * 0 means the job is already finished (or gone) and the caller should stop.
 */
@Dao
interface ImageJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ImageJobEntity)

    @Query("SELECT * FROM image_jobs WHERE id = :id")
    suspend fun getById(id: String): ImageJobEntity?

    @Query("SELECT * FROM image_jobs WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): ImageJobEntity?

    @Query("SELECT * FROM image_jobs WHERE conversationId = :conversationId")
    fun observeForConversation(conversationId: String): Flow<List<ImageJobEntity>>

    @Query("SELECT * FROM image_jobs WHERE messageId IN (:messageIds)")
    suspend fun getForMessages(messageIds: List<String>): List<ImageJobEntity>

    /** Jobs that haven't reached a terminal state, e.g. to re-enqueue their work after the app restarts. */
    @Query("SELECT * FROM image_jobs WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun getActive(): List<ImageJobEntity>

    @Query(
        """UPDATE image_jobs SET remoteJobId = :remoteJobId, status = 'SUBMITTED'
           WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"""
    )
    suspend fun markSubmitted(id: String, remoteJobId: String): Int

    @Query(
        """UPDATE image_jobs SET status = :status, queuePosition = :queuePosition, pass = :pass, step = :step,
               totalSteps = :totalSteps, startedAt = COALESCE(:startedAt, startedAt)
           WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"""
    )
    suspend fun markProgress(
        id: String,
        status: ImageJobStatus,
        queuePosition: Int?,
        pass: Int?,
        step: Int?,
        totalSteps: Int?,
        startedAt: Long?,
    ): Int

    @Query("UPDATE image_jobs SET previewPath = :path WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
    suspend fun setPreview(id: String, path: String?): Int

    @Query(
        """UPDATE image_jobs SET status = 'SUCCEEDED', completedAt = :completedAt,
               startedAt = COALESCE(:startedAt, startedAt), previewPath = NULL
           WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"""
    )
    suspend fun markSucceeded(id: String, startedAt: Long?, completedAt: Long): Int

    @Query(
        """UPDATE image_jobs SET status = :status, errorCode = :errorCode, errorMessage = :errorMessage,
               completedAt = :completedAt, previewPath = NULL
           WHERE id = :id AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"""
    )
    suspend fun markFinishedUnsuccessfully(
        id: String,
        status: ImageJobStatus,
        errorCode: String?,
        errorMessage: String?,
        completedAt: Long,
    ): Int

    /** After a cancel, records whether the server could still be stopped (only meaningful once cancelled). */
    @Query("UPDATE image_jobs SET errorCode = :errorCode, errorMessage = :errorMessage WHERE id = :id AND status = 'CANCELLED'")
    suspend fun annotateCancelled(id: String, errorCode: String, errorMessage: String): Int

    /** Starts a finished job over (Retry), with freshly resolved parameters. */
    @Query(
        """UPDATE image_jobs SET status = 'QUEUED_LOCAL', paramsJson = :paramsJson, remoteJobId = NULL,
               queuePosition = NULL, pass = NULL, step = NULL, totalSteps = NULL, previewPath = NULL,
               errorCode = NULL, errorMessage = NULL, createdAt = :createdAt, startedAt = NULL, completedAt = NULL
           WHERE id = :id AND status IN ('FAILED', 'CANCELLED')"""
    )
    suspend fun resetForRetry(id: String, paramsJson: String, createdAt: Long): Int
}
