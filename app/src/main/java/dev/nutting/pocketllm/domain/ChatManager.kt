package dev.nutting.pocketllm.domain

import android.util.Log
import dev.nutting.pocketllm.data.local.entity.CompactionSummaryEntity
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import dev.nutting.pocketllm.data.local.entity.MessageEntity
import dev.nutting.pocketllm.data.local.entity.ToolDefinitionEntity
import dev.nutting.pocketllm.data.remote.ApiException
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.model.ChatCompletionRequest
import dev.nutting.pocketllm.data.remote.model.ChatContent
import dev.nutting.pocketllm.data.remote.model.ChatMessage
import dev.nutting.pocketllm.data.remote.model.FunctionDefinition
import dev.nutting.pocketllm.data.remote.model.ToolCall
import dev.nutting.pocketllm.data.remote.model.ToolDefinitionParam
import dev.nutting.pocketllm.data.remote.model.Usage
import dev.nutting.pocketllm.data.local.dao.CompactionSummaryDao
import dev.nutting.pocketllm.data.local.dao.ToolDefinitionDao
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.domain.tool.ToolExecutor
import dev.nutting.pocketllm.util.TokenCounter
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

sealed interface StreamState {
    data class Delta(val content: String, val thinkingContent: String? = null) : StreamState
    data class Complete(val message: MessageEntity) : StreamState
    data class Error(val error: String) : StreamState
    data class ToolCallsPending(val toolCalls: List<ToolCall>) : StreamState
    data class ToolCallResult(val toolCallId: String, val toolName: String, val result: String) : StreamState
    data object Compacting : StreamState
}

class ChatManager(
    private val serverRepository: ServerRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val apiClient: OpenAiApiClient,
    private val compactionSummaryDao: CompactionSummaryDao? = null,
    private val toolDefinitionDao: ToolDefinitionDao? = null,
    private val localLlmClient: LocalLlmClient? = null,
) {
    private var currentJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "ChatManager"
    }

    private val isLocalServer: (String) -> Boolean = { it == LocalLlmClient.LOCAL_SERVER_ID }

    var toolApprovalCallback: (suspend (List<ToolCall>) -> Boolean)? = null

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
        imageDataUrls: List<String> = emptyList(),
        contextWindowSize: Int? = null,
    ): Flow<StreamState> = flow {
        try {
            // Resolve server details (skip for local models)
            val isLocal = isLocalServer(serverId)
            val server = if (isLocal) null else {
                serverRepository.getById(serverId).first()
                    ?: throw IllegalStateException("Server not found: $serverId")
            }
            val apiKey = if (!isLocal && server?.hasApiKey == true) {
                serverRepository.getApiKey(serverId).first()
            } else null

            // Ensure local model is loaded
            if (isLocal) {
                localLlmClient?.ensureModelLoaded(modelId)
                    ?: throw IllegalStateException("Local LLM not available")
            }

            // Get conversation to find active leaf
            val conversation = conversationRepository.getById(conversationId).first()
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            // Save user message if content is non-empty (empty = regenerate)
            val parentId = conversation.activeLeafMessageId
            val parentMessage = parentId?.let { messageRepository.getById(it) }

            val anchorMessage = if (content.isNotEmpty()) {
                val imageUrisStr = imageDataUrls.takeIf { it.isNotEmpty() }?.joinToString("|")
                val userMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    parentMessageId = parentId,
                    role = "user",
                    content = content,
                    imageUris = imageUrisStr,
                    depth = (parentMessage?.depth ?: -1) + 1,
                    createdAt = System.currentTimeMillis(),
                )
                messageRepository.insertMessage(userMessage)
                conversationRepository.updateActiveLeaf(conversationId, userMessage.id)
                userMessage
            } else {
                parentMessage ?: throw IllegalStateException("No parent message for regeneration")
            }

            // Get enabled tools
            val toolDefs = toolDefinitionDao?.getEnabledForConversation(conversationId)?.first()
                ?: emptyList()
            val toolParams = toolDefs.map { it.toToolParam() }.takeIf { it.isNotEmpty() }

            // Build message list from active branch
            val branchMessages = messageRepository.getActiveBranch(anchorMessage.id).first()

            // Check if compaction is needed — account for prior compaction coverage
            val contextWindow = contextWindowSize ?: ((maxTokens ?: 2048) * 4)
            val latestCompaction = compactionSummaryDao?.getLatest(conversationId)
            val lastCompactedCount = latestCompaction?.compactedMessageCount ?: 0
            val uncompactedMessages = if (lastCompactedCount > 0 && lastCompactedCount < branchMessages.size) {
                branchMessages.drop(lastCompactedCount)
            } else {
                branchMessages
            }
            val estimatedTokens = if (latestCompaction != null) {
                // Prior summary replaces compacted messages in the context
                TokenCounter.estimateTokens(latestCompaction.summary) + TokenCounter.estimateTokens(uncompactedMessages)
            } else {
                TokenCounter.estimateTokens(uncompactedMessages)
            }
            val compactionThreshold = (contextWindow * 0.75).toInt()

            val effectiveMessages = if (estimatedTokens > compactionThreshold && branchMessages.size > 4) {
                val recentMessages = branchMessages.takeLast(4)
                val autoInsertBeforeId = recentMessages.first().id
                // Only compact messages since last compaction (plus keep last 4 for context)
                val newMessagesToCompact = if (lastCompactedCount > 0 && lastCompactedCount < branchMessages.size - 4) {
                    branchMessages.drop(lastCompactedCount).dropLast(4)
                } else {
                    branchMessages.dropLast(4)
                }
                val totalCompactedCount = branchMessages.size - 4

                emit(StreamState.Compacting)

                val summary = if (isLocal) {
                    tryCompactMessagesLocal(
                        messagesToCompact = newMessagesToCompact,
                        totalCompactedCount = totalCompactedCount,
                        conversationId = conversationId,
                        modelId = modelId,
                        priorSummary = latestCompaction?.summary,
                        insertedBeforeMessageId = autoInsertBeforeId,
                    )
                } else {
                    tryCompactMessages(
                        messagesToCompact = newMessagesToCompact,
                        totalCompactedCount = totalCompactedCount,
                        conversationId = conversationId,
                        baseUrl = server!!.baseUrl,
                        apiKey = apiKey,
                        timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                        modelId = modelId,
                        priorSummary = latestCompaction?.summary,
                        insertedBeforeMessageId = autoInsertBeforeId,
                    )
                }

                if (summary != null) {
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
                    branchMessages
                }
            } else {
                branchMessages
            }

            var chatMessages = buildList {
                systemPrompt?.takeIf { it.isNotBlank() }?.let {
                    add(ChatMessage(role = "system", content = ChatContent.Text(it)))
                }
                effectiveMessages.forEach { msg ->
                    val chatContent = if (msg.imageUris != null && msg.role == "user") {
                        val parts = buildList<dev.nutting.pocketllm.data.remote.model.ContentPart> {
                            if (msg.content.isNotBlank()) {
                                add(dev.nutting.pocketllm.data.remote.model.ContentPart.TextPart(msg.content))
                            }
                            msg.imageUris.split("|").forEach { dataUrl ->
                                add(
                                    dev.nutting.pocketllm.data.remote.model.ContentPart.ImagePart(
                                        dev.nutting.pocketllm.data.remote.model.ImageUrl(url = dataUrl)
                                    )
                                )
                            }
                        }
                        ChatContent.Parts(parts)
                    } else {
                        ChatContent.Text(msg.content)
                    }
                    add(
                        ChatMessage(
                            role = msg.role,
                            content = chatContent,
                            toolCallId = msg.toolCallId,
                        )
                    )
                }
            }

            // Streaming + tool call loop
            var lastParentMessage = anchorMessage
            var continueLoop = true

            while (continueLoop) {
                continueLoop = false

                val accumulatedContent = StringBuilder()
                val accumulatedThinking = StringBuilder()
                val accumulatedToolCalls = mutableMapOf<Int, AccumulatedToolCall>()
                var usage: Usage? = null
                var finishReason: String? = null

                currentJob = currentCoroutineContext()[Job]

                // Choose streaming source: local LLM or remote API
                val chunkFlow = if (isLocal) {
                    localLlmClient!!.streamChatCompletion(
                        messages = chatMessages,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        topP = topP,
                    )
                } else {
                    val request = ChatCompletionRequest(
                        model = modelId,
                        messages = chatMessages,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        topP = topP,
                        frequencyPenalty = frequencyPenalty,
                        presencePenalty = presencePenalty,
                        stream = true,
                        tools = toolParams,
                    )
                    apiClient.streamChatCompletion(
                        baseUrl = server!!.baseUrl,
                        apiKey = apiKey,
                        timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                        request = request,
                    )
                }

                chunkFlow.collect { chunk ->
                    val choice = chunk.choices.firstOrNull()
                    val delta = choice?.delta
                    choice?.finishReason?.let { finishReason = it }

                    delta?.content?.let {
                        accumulatedContent.append(it)
                        emit(StreamState.Delta(content = it))
                    }
                    delta?.reasoningContent?.let {
                        accumulatedThinking.append(it)
                        emit(StreamState.Delta(content = "", thinkingContent = it))
                    }
                    delta?.toolCalls?.forEach { tc ->
                        val acc = accumulatedToolCalls.getOrPut(tc.index) {
                            AccumulatedToolCall()
                        }
                        tc.id?.let { acc.id = it }
                        tc.function?.name?.let { acc.name = (acc.name ?: "") + it }
                        tc.function?.arguments?.let { acc.arguments.append(it) }
                    }
                    chunk.usage?.let { usage = it }
                }

                // Detect thinking in <think> tags
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

                val resolvedToolCalls = accumulatedToolCalls.values.mapNotNull { it.toToolCall() }

                if (finishReason == "tool_calls" && resolvedToolCalls.isNotEmpty()) {
                    // Save assistant message with tool calls
                    val toolCallsJson = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolCall.serializer()),
                        resolvedToolCalls,
                    )
                    val assistantMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        parentMessageId = lastParentMessage.id,
                        role = "assistant",
                        content = finalContent,
                        thinkingContent = thinkingContent,
                        toolCallsJson = toolCallsJson,
                        serverProfileId = serverId,
                        modelId = modelId,
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        totalTokens = usage?.totalTokens,
                        depth = lastParentMessage.depth + 1,
                        createdAt = System.currentTimeMillis(),
                    )
                    messageRepository.insertMessage(assistantMsg)
                    conversationRepository.updateActiveLeaf(conversationId, assistantMsg.id)

                    emit(StreamState.ToolCallsPending(resolvedToolCalls))

                    // Check approval
                    val approved = toolApprovalCallback?.invoke(resolvedToolCalls) ?: true

                    if (approved) {
                        // Execute tool calls and send results
                        var currentParent = assistantMsg
                        val toolMessages = mutableListOf<ChatMessage>()

                        for (tc in resolvedToolCalls) {
                            val result = ToolExecutor.execute(tc.function.name, tc.function.arguments)
                            emit(StreamState.ToolCallResult(tc.id, tc.function.name, result))

                            val toolMsg = MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                parentMessageId = currentParent.id,
                                role = "tool",
                                content = result,
                                toolCallId = tc.id,
                                depth = currentParent.depth + 1,
                                createdAt = System.currentTimeMillis(),
                            )
                            messageRepository.insertMessage(toolMsg)
                            conversationRepository.updateActiveLeaf(conversationId, toolMsg.id)
                            currentParent = toolMsg

                            toolMessages.add(
                                ChatMessage(
                                    role = "tool",
                                    content = ChatContent.Text(result),
                                    toolCallId = tc.id,
                                )
                            )
                        }

                        // Rebuild messages for continuation
                        chatMessages = chatMessages + ChatMessage(
                            role = "assistant",
                            content = ChatContent.Text(finalContent),
                        ) + toolMessages

                        lastParentMessage = currentParent
                        continueLoop = true
                    } else {
                        emit(StreamState.Complete(assistantMsg))
                    }
                } else {
                    // Normal completion - save assistant message
                    val assistantMessage = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        parentMessageId = lastParentMessage.id,
                        role = "assistant",
                        content = finalContent,
                        thinkingContent = thinkingContent,
                        serverProfileId = serverId,
                        modelId = modelId,
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        totalTokens = usage?.totalTokens,
                        depth = lastParentMessage.depth + 1,
                        createdAt = System.currentTimeMillis(),
                    )
                    messageRepository.insertMessage(assistantMessage)
                    conversationRepository.updateActiveLeaf(conversationId, assistantMessage.id)

                    emit(StreamState.Complete(assistantMessage))
                }
            }
        } catch (e: ApiException.StreamDisconnected) {
            Log.e(TAG, "Stream disconnected, partial=${e.partialContent.length} chars", e)
            if (e.partialContent.isNotBlank()) {
                emit(StreamState.Error("Connection lost. Partial response was received."))
            } else {
                emit(StreamState.Error(e.message ?: "Connection lost during streaming"))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "API error during chat: ${e.message}", e)
            emit(StreamState.Error(e.message ?: "Request failed"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during chat", e)
            emit(StreamState.Error(e.message ?: "Unknown error"))
        }
    }

    private fun ToolDefinitionEntity.toToolParam(): ToolDefinitionParam {
        val schema = json.parseToJsonElement(parametersSchemaJson) as JsonObject
        return ToolDefinitionParam(
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = schema,
            ),
        )
    }

    private class AccumulatedToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toToolCall(): ToolCall? {
            val id = id ?: return null
            val name = name ?: return null
            return ToolCall(
                id = id,
                function = dev.nutting.pocketllm.data.remote.model.FunctionCall(
                    name = name,
                    arguments = arguments.toString(),
                ),
            )
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
        if (branchMessages.size < 2) return null

        val latestCompaction = compactionSummaryDao?.getLatest(conversationId)
        val lastCompactedCount = latestCompaction?.compactedMessageCount ?: 0

        val insertedBeforeMessageId = branchMessages[branchMessages.size - 2].id
        val totalCompactedCount = branchMessages.size - 2
        // Only send new messages since last compaction to the summarizer
        val newMessagesToCompact = if (lastCompactedCount > 0 && lastCompactedCount < branchMessages.size - 2) {
            branchMessages.drop(lastCompactedCount).dropLast(2)
        } else {
            branchMessages.dropLast(2)
        }

        if (newMessagesToCompact.isEmpty() && latestCompaction == null) return null

        return if (isLocalServer(serverId)) {
            tryCompactMessagesLocal(
                messagesToCompact = newMessagesToCompact,
                totalCompactedCount = totalCompactedCount,
                conversationId = conversationId,
                modelId = modelId,
                priorSummary = latestCompaction?.summary,
                insertedBeforeMessageId = insertedBeforeMessageId,
            )
        } else {
            val server = serverRepository.getById(serverId).first() ?: return null
            val apiKey = if (server.hasApiKey) serverRepository.getApiKey(serverId).first() else null
            tryCompactMessages(
                messagesToCompact = newMessagesToCompact,
                totalCompactedCount = totalCompactedCount,
                conversationId = conversationId,
                baseUrl = server.baseUrl,
                apiKey = apiKey,
                timeoutSeconds = server.requestTimeoutSeconds.toLong(),
                modelId = modelId,
                priorSummary = latestCompaction?.summary,
                insertedBeforeMessageId = insertedBeforeMessageId,
            )
        }
    }

    private fun buildCompactionPrompt(
        newMessages: List<MessageEntity>,
        priorSummary: String?,
    ): List<ChatMessage> {
        val newText = newMessages.joinToString("\n") { "${it.role}: ${it.content}" }

        return if (priorSummary != null) {
            listOf(
                ChatMessage(
                    role = "system",
                    content = ChatContent.Text(
                        "You have an existing summary of an earlier portion of a conversation. New messages have occurred since that summary. " +
                        "Produce an updated summary that integrates BOTH the existing summary AND the new messages. " +
                        "Cover every topic, key fact, decision, and piece of content. Organize chronologically. " +
                        "The summary must preserve enough context to continue the conversation coherently. " +
                        "Respond with only the updated summary, no preamble."
                    ),
                ),
                ChatMessage(
                    role = "user",
                    content = ChatContent.Text(
                        "EXISTING SUMMARY:\n$priorSummary\n\nNEW MESSAGES:\n$newText"
                    ),
                ),
            )
        } else {
            listOf(
                ChatMessage(
                    role = "system",
                    content = ChatContent.Text(
                        "Summarize the ENTIRE following conversation from beginning to end. " +
                        "Cover every topic, key fact, decision, and piece of content discussed — do not focus only on recent messages. " +
                        "Organize chronologically. The summary must preserve enough context to continue the conversation coherently. " +
                        "Respond with only the summary, no preamble."
                    ),
                ),
                ChatMessage(
                    role = "user",
                    content = ChatContent.Text(newText),
                ),
            )
        }
    }

    private suspend fun tryCompactMessages(
        messagesToCompact: List<MessageEntity>,
        totalCompactedCount: Int,
        conversationId: String,
        baseUrl: String,
        apiKey: String?,
        timeoutSeconds: Long,
        modelId: String,
        priorSummary: String? = null,
        insertedBeforeMessageId: String? = null,
    ): String? {
        if (messagesToCompact.isEmpty()) return null

        val request = ChatCompletionRequest(
            model = modelId,
            messages = buildCompactionPrompt(messagesToCompact, priorSummary),
            maxTokens = 2048,
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
                        compactedMessageCount = totalCompactedCount,
                        insertedBeforeMessageId = insertedBeforeMessageId,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            summary
        } catch (e: Exception) {
            Log.w(TAG, "Compaction failed, continuing without summary", e)
            null
        }
    }

    private suspend fun tryCompactMessagesLocal(
        messagesToCompact: List<MessageEntity>,
        totalCompactedCount: Int,
        conversationId: String,
        modelId: String,
        priorSummary: String? = null,
        insertedBeforeMessageId: String? = null,
    ): String? {
        if (messagesToCompact.isEmpty()) return null
        val client = localLlmClient ?: return null

        val messages = buildCompactionPrompt(messagesToCompact, priorSummary)

        return try {
            client.ensureModelLoaded(modelId)
            val summary = client.chatCompletion(
                messages = messages,
                maxTokens = 2048,
                temperature = 0.3f,
            ).trim()

            if (summary.isNotBlank()) {
                compactionSummaryDao?.insert(
                    CompactionSummaryEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        summary = summary,
                        compactedMessageCount = totalCompactedCount,
                        insertedBeforeMessageId = insertedBeforeMessageId,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                summary
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Local compaction failed, continuing without summary", e)
            null
        }
    }

    fun stopGeneration() {
        localLlmClient?.cancel()
        currentJob?.cancel()
        currentJob = null
    }
}
