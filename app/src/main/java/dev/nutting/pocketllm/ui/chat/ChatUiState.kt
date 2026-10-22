package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.remote.image.SdCppCapabilities
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ToolCall
import dev.nutting.pocketllm.domain.LocalModelState
import dev.nutting.pocketllm.domain.image.ImageParameters
import dev.nutting.pocketllm.llm.InferenceStatus

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val currentStreamingContent: String = "",
    val currentStreamingThinking: String = "",
    val isStreaming: Boolean = false,
    /** Whether the in-flight reply runs on-device (independent of the model now selected). */
    val isStreamingLocal: Boolean = false,
    val selectedServer: ServerProfileEntity? = null,
    val selectedModelId: String? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val availableServers: List<ServerProfileEntity> = emptyList(),
    val serversLoaded: Boolean = false,
    val error: String? = null,
    val isLoadingModels: Boolean = false,
    val conversationId: String? = null,
    val conversationTitle: String = "New Chat",
    val conversationParams: ConversationParameters = ConversationParameters(),
    val defaultParams: ConversationParameters = ConversationParameters(),
    val showConversationSettings: Boolean = false,
    val estimatedTokensUsed: Int = 0,
    val compactionThresholdPct: Int = 75,
    val pendingToolCalls: List<ToolCall> = emptyList(),
    val toolCallResults: Map<String, String> = emptyMap(),
    val availableTools: List<ToolDefinitionEntity> = emptyList(),
    val presets: List<ParameterPresetEntity> = emptyList(),
    val messageFontSizeSp: Int = 16,
    val editingMessage: MessageEntity? = null,
    val compactionSummaries: List<CompactionSummaryEntity> = emptyList(),
    val isCompacting: Boolean = false,
    // Local LLM
    val useLocalModel: Boolean = false,
    val localModels: List<LocalModel> = emptyList(),
    val activeLocalModelId: String? = null,
    /** What the on-device engine is doing for the pending reply; null when idle or using a server. */
    val localStatus: InferenceStatus? = null,
    /** Which on-device model is in memory (or loading / failed to load). */
    val localModelState: LocalModelState = LocalModelState.NotLoaded,
    /** When the pending reply was requested (epoch ms), for showing elapsed time. */
    val streamStartedAtMs: Long? = null,
    // Image generation
    /** Image replies in this conversation by assistant message id. */
    val imageReplies: Map<String, ImageReplyState> = emptyMap(),
    /** Servers set up for image generation (chat servers are in [availableServers]). */
    val imageServers: List<ServerProfileEntity> = emptyList(),
    val selectedImageServer: ServerProfileEntity? = null,
    /** The composer sends prompts to the image server instead of the chat model. */
    val imageMode: Boolean = false,
    /** How the selected image server is spoken to, once probed. */
    val imageDialect: ImageDialect? = null,
    /** This conversation's image settings (null fields = server defaults). */
    val imageParams: ImageParameters = ImageParameters(),
    /** What the selected image server offers (samplers, limits, defaults), when it's stable-diffusion.cpp. */
    val imageCapabilities: SdCppCapabilities? = null,
) {
    /** Prompts go to the image server: image mode is on and there's an image server to use. */
    val imageModeActive: Boolean get() = imageMode && selectedImageServer != null
}
