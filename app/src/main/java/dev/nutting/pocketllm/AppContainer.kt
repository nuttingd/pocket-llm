package dev.nutting.pocketllm

import android.content.Context
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.preferences.SettingsDataStore
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ChatManager

class AppContainer(context: Context) {

    private val database = PocketLlmDatabase.create(context)
    private val apiClient = OpenAiApiClient()
    private val encryptedDataStore = EncryptedDataStore(context)
    private val settingsDataStore = SettingsDataStore(context)

    val serverRepository = ServerRepository(
        dao = database.serverProfileDao(),
        encryptedDataStore = encryptedDataStore,
        apiClient = apiClient,
    )

    val conversationRepository = ConversationRepository(
        dao = database.conversationDao(),
    )

    val messageRepository = MessageRepository(
        dao = database.messageDao(),
    )

    val settingsRepository = SettingsRepository(
        dataStore = settingsDataStore,
    )

    val toolDefinitionDao = database.toolDefinitionDao()
    val parameterPresetDao = database.parameterPresetDao()

    val chatManager = ChatManager(
        serverRepository = serverRepository,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        apiClient = apiClient,
        compactionSummaryDao = database.compactionSummaryDao(),
        toolDefinitionDao = toolDefinitionDao,
    )
}
