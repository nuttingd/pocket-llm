package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.data.remote.model.Usage
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface StreamState {
    data class Delta(val content: String, val thinkingContent: String? = null) : StreamState
    data class Complete(val message: MessageEntity) : StreamState
    data class Error(val error: String) : StreamState
}

class ChatManager(
    private val serverRepository: ServerRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val apiClient: OpenAiApiClient,
) {
    private var currentJob: Job? = null

    fun sendMessage(
        conversationId: String,
        content: String,
        serverId: String,
        modelId: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        topP: Float? = null,
        frequencyPenalty: Float? = null,
        presencePenalty: Float? = null,
    ): Flow<StreamState> = flow {
        try {
            // Get server details
            val server = serverRepository.getById(serverId).first()
                ?: throw IllegalStateException("Server not found: $serverId")
            val apiKey = if (server.hasApiKey) {
                serverRepository.getApiKey(serverId).first()
            } else null

            // Get conversation to find active leaf
            val conversation = conversationRepository.getById(conversationId).first()
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            // Save user message
            val parentId = conversation.activeLeafMessageId
            val parentMessage = parentId?.let { messageRepository.getById(it) }
            val userMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                parentMessageId = parentId,
                role = "user",
                content = content,
                depth = (parentMessage?.depth ?: -1) + 1,
                createdAt = System.currentTimeMillis(),
            )
            messageRepository.insertMessage(userMessage)
            conversationRepository.updateActiveLeaf(conversationId, userMessage.id)

            // Build message list from active branch
            val branchMessages = messageRepository.getActiveBranch(userMessage.id).first()
            val chatMessages = buildList {
                systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    add(ChatMessage(role = "system", content = ChatContent.Text(it)))
                }
                branchMessages.forEach { msg ->
                    add(ChatMessage(role = msg.role, content = ChatContent.Text(msg.content)))
                }
            }

            // Build request
            val request = ChatCompletionRequest(
                model = modelId,
                messages = chatMessages,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
                stream = true,
            )

            // Stream response
            var accumulatedContent = StringBuilder()
            var accumulatedThinking = StringBuilder()
            var usage: Usage? = null

            coroutineScope {
                currentJob = launch {
                    apiClient.streamChatCompletion(
                        baseUrl = server.baseUrl,
                        apiKey = apiKey,
                        timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                        request = request,
                    ).collect { chunk ->
                        val delta = chunk.choices.firstOrNull()?.delta
                        delta?.content?.let {
                            accumulatedContent.append(it)
                            emit(StreamState.Delta(content = it))
                        }
                        delta?.reasoningContent?.let {
                            accumulatedThinking.append(it)
                            emit(StreamState.Delta(content = "", thinkingContent = it))
                        }
                        chunk.usage?.let { usage = it }
                    }
                }
                currentJob?.join()
            }

            // Detect thinking in <think> tags if no reasoning_content was provided
            var finalContent = accumulatedContent.toString()
            var thinkingContent = accumulatedThinking.toString().takeIf { it.isNotBlank() }

            if (thinkingContent == null) {
                val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
                val match = thinkRegex.find(finalContent)
                if (match != null) {
                    thinkingContent = match.groupValues[1].trim()
                    finalContent = finalContent.replace(match.value, "").trim()
                }
            }

            // Save assistant message
            val assistantMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                parentMessageId = userMessage.id,
                role = "assistant",
                content = finalContent,
                thinkingContent = thinkingContent,
                serverProfileId = serverId,
                modelId = modelId,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens,
                totalTokens = usage?.totalTokens,
                depth = userMessage.depth + 1,
                createdAt = System.currentTimeMillis(),
            )
            messageRepository.insertMessage(assistantMessage)
            conversationRepository.updateActiveLeaf(conversationId, assistantMessage.id)

            emit(StreamState.Complete(assistantMessage))
        } catch (e: Exception) {
            emit(StreamState.Error(e.message ?: "Unknown error"))
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentJob = null
    }
}
