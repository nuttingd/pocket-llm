package dev.nutting.pocketllm.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object ConversationList

@Serializable
data class Chat(val conversationId: String? = null)

@Serializable
object ServerConfig

@Serializable
data class ServerEdit(val serverId: String? = null)

@Serializable
object Settings
