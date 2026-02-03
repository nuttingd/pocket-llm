package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val hasApiKey: Boolean = false,
    val requestTimeoutSeconds: Int = 60,
    val createdAt: Long,
    val updatedAt: Long,
)
