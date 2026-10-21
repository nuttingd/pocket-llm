package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One image generation or edit request: the source of truth for the job's lifecycle, so it survives
 * process death and drives the reply's UI on re-entry. `serverId` is deliberately not a foreign key:
 * deleting a server must not erase the history of finished jobs.
 */
@Entity(
    tableName = "image_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["messageId"], unique = true),
        Index("status"),
    ],
)
data class ImageJobEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** The pending assistant message that shows this job and, on success, its images. */
    val messageId: String,
    val serverId: String,
    val modelId: String,
    val dialect: ImageDialect,
    val kind: ImageJobKind,
    /** Exactly what the user typed (never contains server-specific markup). */
    val prompt: String,
    /** The resolved parameters actually sent, including the concrete seed. */
    val paramsJson: String,
    /** For edits: the user message whose attachments are the input images. */
    val sourceMessageId: String? = null,
    /** The server's job id (sd-server); set right after submission so a restart resumes polling. */
    val remoteJobId: String? = null,
    val status: ImageJobStatus,
    val queuePosition: Int? = null,
    val pass: Int? = null,
    val step: Int? = null,
    val totalSteps: Int? = null,
    val previewPath: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

/** How the app talks to an image server: sd-server's native async API, or the OpenAI images API. */
enum class ImageDialect { SD_CPP, OPENAI_IMAGES }

enum class ImageJobKind { GENERATE, EDIT }

enum class ImageJobStatus {
    QUEUED_LOCAL, SUBMITTED, GENERATING, SAVING, SUCCEEDED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}
