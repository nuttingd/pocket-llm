package dev.nutting.pocketllm.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val hasApiKey: Boolean = false,
    val requestTimeoutSeconds: Int = 120,
    /** Overall deadline for one image job; image generation takes far longer than a chat reply. */
    @ColumnInfo(defaultValue = "1800") val imageTimeoutSeconds: Int = 1800,
    /** What the server is for: chatting (OpenAI chat completions) or generating images. */
    @ColumnInfo(defaultValue = "CHAT") val kind: ServerKind = ServerKind.CHAT,
    /** The model to ask an image server for; null on a stable-diffusion.cpp server, which serves one model. */
    val imageModelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ServerKind { CHAT, IMAGE }
