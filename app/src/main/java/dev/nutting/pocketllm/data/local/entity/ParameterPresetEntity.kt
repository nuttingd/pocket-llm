package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parameter_presets")
data class ParameterPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isBuiltIn: Boolean = false,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
)
