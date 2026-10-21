package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/** A user override of whether a server's model generates images (auto-detection is never stored). */
@Entity(
    tableName = "model_classifications",
    primaryKeys = ["serverId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ModelClassificationEntity(
    val serverId: String,
    val modelId: String,
    val isImageModel: Boolean,
)
