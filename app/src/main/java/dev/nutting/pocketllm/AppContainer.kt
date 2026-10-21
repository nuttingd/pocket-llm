package dev.nutting.pocketllm

import android.content.Context
import dev.nutting.pocketllm.data.local.PocketLlmDatabase
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.preferences.SettingsDataStore
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.huggingface.HuggingFaceClient
import dev.nutting.pocketllm.data.remote.image.OpenAiImagesClient
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import dev.nutting.pocketllm.data.repository.ConversationRepository
import dev.nutting.pocketllm.data.repository.MessageRepository
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.ChatManager
import dev.nutting.pocketllm.domain.GenerationManager
import dev.nutting.pocketllm.domain.LocalLlmClient
import dev.nutting.pocketllm.domain.image.ImageJobLauncher
import dev.nutting.pocketllm.domain.image.ImageJobRunner
import dev.nutting.pocketllm.domain.image.ImageJobScheduler
import dev.nutting.pocketllm.domain.image.ImagePreviewStore
import dev.nutting.pocketllm.domain.image.ImageStore
import dev.nutting.pocketllm.domain.image.ModelClassifier
import dev.nutting.pocketllm.domain.image.WorkManagerImageJobScheduler
import dev.nutting.pocketllm.domain.image.loadEditSources
import dev.nutting.pocketllm.llm.LlmEngine
import dev.nutting.pocketllm.util.ModelDownloadManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

class AppContainer(context: Context) {

    /** For work started from a screen that must finish even if the user leaves it (imports, model loads). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database = PocketLlmDatabase.create(context)
    private val apiClient = OpenAiApiClient()
    val encryptedDataStore = EncryptedDataStore(context)
    private val settingsDataStore = SettingsDataStore(context)

    val serverRepository = ServerRepository(
        dao = database.serverProfileDao(),
        encryptedDataStore = encryptedDataStore,
        apiClient = apiClient,
    )

    /** Generated images are files under filesDir/images, referenced from the generated_images table. */
    val imageStore = ImageStore(context.filesDir)

    val conversationRepository = ConversationRepository(
        dao = database.conversationDao(),
        imageStore = imageStore,
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
    val imageJobDao = database.imageJobDao()
    val generatedImageDao = database.generatedImageDao()
    val modelClassificationDao = database.modelClassificationDao()

    // Remote image generation
    val sdCppImageClient = SdCppImageClient()
    val openAiImagesClient = OpenAiImagesClient()
    val modelClassifier = ModelClassifier(
        sdCpp = sdCppImageClient,
        dao = modelClassificationDao,
        apiKey = { serverId -> serverRepository.getApiKey(serverId).first() },
    )
    val imageJobScheduler: ImageJobScheduler = WorkManagerImageJobScheduler(context.applicationContext)
    val imageJobRunner = ImageJobRunner(
        db = database,
        server = { id -> serverRepository.getById(id).first() },
        apiKey = { serverId -> serverRepository.getApiKey(serverId).first() },
        sdCpp = sdCppImageClient,
        openAi = openAiImagesClient,
        store = imageStore,
        previews = ImagePreviewStore(context.cacheDir),
        previewMode = { server ->
            // `proj` is the cheapest mode (~16 KB a frame), and sd-server reports step progress only with a preview
            "proj".takeIf { it in modelClassifier.serverInfo(server).capabilities?.previewModes.orEmpty() }
        },
        livePreview = { settingsRepository.getImageLivePreview().first() },
        sourceImages = { job -> loadEditSources(database.messageDao(), job.sourceMessageId) },
    )
    val imageJobLauncher = ImageJobLauncher(
        db = database,
        classifier = modelClassifier,
        scheduler = imageJobScheduler,
        sdCpp = sdCppImageClient,
        apiKey = { serverId -> serverRepository.getApiKey(serverId).first() },
        appScope = applicationScope,
    )

    // Local LLM
    val modelsDir: File = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }
    val llmEngine = LlmEngine()
    val localModelStore = LocalModelStore(context)
    val huggingFaceClient = HuggingFaceClient()
    val modelDownloadManager = ModelDownloadManager(
        context = context.applicationContext,
        localModelStore = localModelStore,
        modelsDir = modelsDir,
    )
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
        imageJobDao = imageJobDao,
        generatedImageDao = generatedImageDao,
    )

    /** Owns in-flight replies so they keep running (and can be re-attached to) after leaving a chat. */
    val generationManager = GenerationManager(
        cancelLocalInference = chatManager::cancelLocalInference,
        generateTitle = chatManager::generateTitle,
    ).also { manager ->
        chatManager.toolApprovalCallback = manager::awaitToolApproval
    }
}
