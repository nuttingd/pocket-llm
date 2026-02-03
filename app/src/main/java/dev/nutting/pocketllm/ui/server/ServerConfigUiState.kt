package dev.nutting.pocketllm.ui.server

import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.model.ModelInfo

data class ServerConfigUiState(
    val servers: List<ServerProfileEntity> = emptyList(),
    val editingServer: EditingServer? = null,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val error: String? = null,
    val isFirstLaunch: Boolean = false,
)

data class EditingServer(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val requestTimeoutSeconds: Int = 60,
)
