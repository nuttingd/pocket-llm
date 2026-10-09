package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ToolCall
import dev.nutting.pocketllm.domain.LocalModelState
import dev.nutting.pocketllm.llm.InferenceStatus

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val currentStreamingContent: String = "",
    val currentStreamingThinking: String = "",
    val isStreaming: Boolean = false,
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
)
