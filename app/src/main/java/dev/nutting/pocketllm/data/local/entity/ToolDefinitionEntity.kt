package dev.nutting.pocketllm.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tool_definitions")
data class ToolDefinitionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parametersSchemaJson: String,
    val isBuiltIn: Boolean = false,
    val isEnabledByDefault: Boolean = true,
)
