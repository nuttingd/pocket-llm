package dev.nutting.pocketllm.domain

import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.data.remote.model.Usage
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.util.TokenCounter
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
    private val compactionSummaryDao: CompactionSummaryDao? = null,
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

            // Check if compaction is needed
            val contextWindow = (maxTokens ?: 2048) * 4
            val estimatedTokens = TokenCounter.estimateTokens(branchMessages)
            val compactionThreshold = (contextWindow * 0.75).toInt()

            val effectiveMessages = if (estimatedTokens > compactionThreshold && branchMessages.size > 4) {
                // Attempt auto-compaction: summarize older messages
                val messagesToCompact = branchMessages.dropLast(4) // Keep recent 4
                val recentMessages = branchMessages.takeLast(4)

                val summary = tryCompactMessages(
                    messagesToCompact = messagesToCompact,
                    conversationId = conversationId,
                    baseUrl = server.baseUrl,
                    apiKey = apiKey,
                    timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                    modelId = modelId,
                )

                if (summary != null) {
                    // Use summary + recent messages
                    listOf(
                        MessageEntity(
                            id = "compaction-summary",
                            conversationId = conversationId,
                            role = "system",
                            content = "Previous conversation summary: $summary",
                            depth = 0,
                            createdAt = System.currentTimeMillis(),
                        )
                    ) + recentMessages
                } else {
                    branchMessages // Compaction failed, use full branch
                }
            } else {
                branchMessages
            }

            val chatMessages = buildList {
                systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    add(ChatMessage(role = "system", content = ChatContent.Text(it)))
                }
                effectiveMessages.forEach { msg ->
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

    suspend fun compactConversation(
        conversationId: String,
        serverId: String,
        modelId: String,
    ): String? {
        val conversation = conversationRepository.getById(conversationId).first() ?: return null
        val leafId = conversation.activeLeafMessageId ?: return null
        val branchMessages = messageRepository.getActiveBranch(leafId).first()
        if (branchMessages.size <= 4) return null

        val server = serverRepository.getById(serverId).first() ?: return null
        val apiKey = if (server.hasApiKey) serverRepository.getApiKey(serverId).first() else null

        val messagesToCompact = branchMessages.dropLast(4)
        return tryCompactMessages(
            messagesToCompact = messagesToCompact,
            conversationId = conversationId,
            baseUrl = server.baseUrl,
            apiKey = apiKey,
            timeoutSeconds = server.requestTimeoutSeconds.toLong(),
            modelId = modelId,
        )
    }

    private suspend fun tryCompactMessages(
        messagesToCompact: List<MessageEntity>,
        conversationId: String,
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long,
        modelId: String,
    ): String? {
        if (messagesToCompact.isEmpty()) return null

        val conversationText = messagesToCompact.joinToString("\n") { "${it.role}: ${it.content}" }
        val request = ChatCompletionRequest(
            model = modelId,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = ChatContent.Text(
                        "Summarize the following conversation concisely, preserving key facts, decisions, and context needed to continue the conversation coherently. Respond with only the summary."
                    ),
                ),
                ChatMessage(
                    role = "user",
                    content = ChatContent.Text(conversationText),
                ),
            ),
            maxTokens = 500,
            temperature = 0.3f,
            stream = false,
        )

        return try {
            val response = apiClient.chatCompletion(
                baseUrl = baseUrl,
                apiKey = apiKey,
                timeoutSeconds = timeoutSeconds,
                request = request,
            )
            val summary = response.choices.firstOrNull()?.message?.content?.trim()
            if (summary != null) {
                compactionSummaryDao?.insert(
                    CompactionSummaryEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        summary = summary,
                        compactedMessageCount = messagesToCompact.size,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            summary
        } catch (_: Exception) {
            null
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentJob = null
    }
}
