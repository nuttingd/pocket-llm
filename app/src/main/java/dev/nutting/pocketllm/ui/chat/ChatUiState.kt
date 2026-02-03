package dev.nutting.pocketllm.ui.chat

import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ParameterPresetEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import dev.nutting.pocketllm.data.remote.model.ToolCall

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val currentStreamingContent: String = "",
    val currentStreamingThinking: String = "",
    val isStreaming: Boolean = false,
    val selectedServer: ServerProfileEntity? = null,
    val selectedModelId: String? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val availableServers: List<ServerProfileEntity> = emptyList(),
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
)
