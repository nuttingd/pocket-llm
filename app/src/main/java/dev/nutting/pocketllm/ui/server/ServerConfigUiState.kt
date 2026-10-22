package dev.nutting.pocketllm.ui.server

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerKind
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.domain.image.ImageServerInfo

data class ServerConfigUiState(
    val servers: List<ServerProfileEntity> = emptyList(),
    /** What each image server is ("stable-diffusion.cpp · Qwen-Image"), by server id, once probed. */
    val imageServerDescriptions: Map<String, String> = emptyMap(),
    val editingServer: EditingServer? = null,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val error: String? = null,
) {
    val chatServers: List<ServerProfileEntity> get() = servers.filter { it.kind == ServerKind.CHAT }
    val imageServers: List<ServerProfileEntity> get() = servers.filter { it.kind == ServerKind.IMAGE }
}

data class EditingServer(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Whether the server already has a stored API key, kept when the key field is left empty. */
    val hasStoredApiKey: Boolean = false,
    val requestTimeoutSeconds: Int = 120,
    /** Overall limit for one image generation; far longer than a chat reply. */
    val imageTimeoutMinutes: Int = 30,
    val kind: ServerKind = ServerKind.CHAT,
    /** Set once the user picks the type, so detection no longer changes it. */
    val kindChosenByUser: Boolean = false,
    /** The model to request from an image server that isn't stable-diffusion.cpp. */
    val imageModelId: String = "",
    val detection: Detection = Detection.Idle,
    /** Models the server lists, offered as image model suggestions. */
    val suggestedModels: List<String> = emptyList(),
) {
    /** An OpenAI images server must be told which model to use; stable-diffusion.cpp serves just one. */
    val needsImageModel: Boolean
        get() = kind == ServerKind.IMAGE && (detection as? Detection.Found)?.info?.dialect != ImageDialect.SD_CPP
}

/** What probing the URL being edited found. */
sealed interface Detection {
    data object Idle : Detection
    data object Checking : Detection
    data class Found(val info: ImageServerInfo) : Detection
    data class Unreachable(val message: String) : Detection
}

/**
 * Applies a probe result to the form. Finding stable-diffusion.cpp makes it an image server, unless the user
 * already chose the type themselves; finding anything else leaves the type alone, since a server that isn't
 * stable-diffusion.cpp may be a chat server or an OpenAI-compatible image server.
 */
internal fun EditingServer.withDetection(detection: Detection): EditingServer {
    val isSdCpp = (detection as? Detection.Found)?.info?.dialect == ImageDialect.SD_CPP
    return copy(
        detection = detection,
        kind = if (isSdCpp && !kindChosenByUser) ServerKind.IMAGE else kind,
    )
}
