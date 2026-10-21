package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A generated image stored as a file (never in the database) and the parameters that produced it. */
@Entity(
    tableName = "generated_images",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId", "idx"], unique = true),
    ],
)
data class GeneratedImageEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    /** Order within the reply. */
    val idx: Int,
    /** Relative to the app's files directory. */
    val filePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** Null when the server doesn't let us choose or report the seed. */
    val seed: Long? = null,
    val prompt: String,
    val paramsJson: String,
    val modelId: String,
    val durationMs: Long? = null,
    val createdAt: Long,
)
