package dev.nutting.pocketllm

import android.content.Context
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.preferences.SettingsDataStore
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ChatManager
import dev.nutting.pocketllm.domain.LocalLlmClient
import dev.nutting.pocketllm.llm.LlmEngine
import java.io.File

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
    val compactionSummaryDao = database.compactionSummaryDao()

    // Local LLM
    val modelsDir: File = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
    val llmEngine = LlmEngine()
    val localModelStore = LocalModelStore(context)
    val localLlmClient = LocalLlmClient(
        llmEngine = llmEngine,
        localModelStore = localModelStore,
        modelsDir = modelsDir,
        apkPath = context.applicationInfo.sourceDir,
    )

    val chatManager = ChatManager(
        serverRepository = serverRepository,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        apiClient = apiClient,
        compactionSummaryDao = compactionSummaryDao,
        toolDefinitionDao = toolDefinitionDao,
        localLlmClient = localLlmClient,
    )
}
