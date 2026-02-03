package dev.nutting.pocketllm.data.repository

import dev.nutting.pocketllm.data.local.dao.ConversationDao
import dev.nutting.pocketllm.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ConversationRepository(
    private val dao: ConversationDao,
) {
    fun getAllSorted(): Flow<List<ConversationEntity>> = dao.getAllByUpdatedAt()

    fun getById(id: String): Flow<ConversationEntity?> = dao.getById(id)

    suspend fun create(conversation: ConversationEntity) = dao.insert(conversation)

    suspend fun rename(id: String, title: String) =
        dao.updateTitle(id, title, System.currentTimeMillis())

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun updateActiveLeaf(id: String, leafId: String?) =
        dao.updateActiveLeaf(id, leafId, System.currentTimeMillis())

    suspend fun exportAsMarkdown(id: String, messageRepository: MessageRepository): String {
        val conversation = dao.getById(id).first() ?: return ""
        val leafId = conversation.activeLeafMessageId ?: return "# ${conversation.title}\n\n*No messages*\n"
        val messages = messageRepository.getActiveBranch(leafId).first()

        return buildString {
            appendLine("# ${conversation.title}")
            appendLine()
            messages.forEach { msg ->
                val role = when (msg.role) {
                    "user" -> "**You**"
                    "assistant" -> "**Assistant**"
                    else -> "**${msg.role}**"
                }
                appendLine("$role:")
                appendLine()
                appendLine(msg.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    suspend fun updateServerAndModel(id: String, serverProfileId: String?, modelId: String?) =
        dao.updateServerAndModel(id, serverProfileId, modelId, System.currentTimeMillis())

    suspend fun updateParameters(
        id: String,
        serverProfileId: String?,
        modelId: String?,
        systemPrompt: String?,
        temperature: Float?,
        maxTokens: Int?,
        topP: Float?,
        frequencyPenalty: Float?,
        presencePenalty: Float?,
    ) = dao.updateParameters(
        id = id,
        serverProfileId = serverProfileId,
        modelId = modelId,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        topP = topP,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        updatedAt = System.currentTimeMillis(),
    )
}
