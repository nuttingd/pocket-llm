package dev.nutting.pocketllm.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.nutting.pocketllm.PocketLlmApplication
import dev.nutting.pocketllm.ui.chat.ChatScreen
import dev.nutting.pocketllm.ui.chat.ChatViewModel
import dev.nutting.pocketllm.ui.conversations.ConversationListViewModel
import dev.nutting.pocketllm.ui.modelmanagement.HuggingFaceBrowserScreen
import dev.nutting.pocketllm.ui.modelmanagement.HuggingFaceBrowserViewModel
import dev.nutting.pocketllm.ui.modelmanagement.ModelManagementScreen
import dev.nutting.pocketllm.ui.modelmanagement.ModelManagementViewModel
import dev.nutting.pocketllm.ui.server.ServerConfigScreen
import dev.nutting.pocketllm.ui.server.ServerConfigViewModel
import dev.nutting.pocketllm.ui.settings.SettingsScreen
import dev.nutting.pocketllm.ui.settings.SettingsViewModel
import dev.nutting.pocketllm.ui.setup.SetupScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PocketLlmApplication).container }

    // Shared by the chat destinations; created outside the NavHost so it's owned by the activity
    val conversationListViewModel = viewModel {
        ConversationListViewModel(
            conversationRepository = container.conversationRepository,
            messageRepository = container.messageRepository,
            settingsRepository = container.settingsRepository,
        )
    }

    val transitionDuration = 300

    NavHost(
        navController = navController,
        startDestination = ConversationList,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(transitionDuration)) + fadeIn(tween(transitionDuration))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(transitionDuration)) + fadeOut(tween(transitionDuration))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(transitionDuration)) + fadeIn(tween(transitionDuration))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(transitionDuration)) + fadeOut(tween(transitionDuration))
        },
    ) {
        composable<ConversationList> {
            // Scoped to the back stack entry: survives visits to other screens, cleared when the entry is popped
            val chatViewModel = viewModel {
                ChatViewModel(
                    chatManager = container.chatManager,
                    generationManager = container.generationManager,
                    conversationRepository = container.conversationRepository,
                    messageRepository = container.messageRepository,
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                    localModelStore = container.localModelStore,
                    toolDefinitionDao = container.toolDefinitionDao,
                    parameterPresetDao = container.parameterPresetDao,
                    compactionSummaryDao = container.compactionSummaryDao,
                )
            }
            ChatScreen(
                viewModel = chatViewModel,
                conversationListViewModel = conversationListViewModel,
                onNavigateToServers = { navController.navigate(ServerConfig) },
                onNavigateToSettings = { navController.navigate(Settings) },
                onNavigateToSetup = { navController.navigate(Setup) },
                onConversationSelected = { id ->
                    if (id != null) {
                        navController.navigate(Chat(conversationId = id)) {
                            popUpTo<ConversationList> { inclusive = true }
                        }
                    } else {
                        navController.navigate(ConversationList) {
                            popUpTo<ConversationList> { inclusive = true }
                        }
                    }
                },
                conversationId = null,
            )
        }
        composable<Chat> { backStackEntry ->
            val route = backStackEntry.toRoute<Chat>()
            // Scoped to the back stack entry: survives visits to other screens, cleared when the entry is popped
            val chatViewModel = viewModel {
                ChatViewModel(
                    chatManager = container.chatManager,
                    generationManager = container.generationManager,
                    conversationRepository = container.conversationRepository,
                    messageRepository = container.messageRepository,
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                    localModelStore = container.localModelStore,
                    toolDefinitionDao = container.toolDefinitionDao,
                    parameterPresetDao = container.parameterPresetDao,
                    compactionSummaryDao = container.compactionSummaryDao,
                )
            }
            ChatScreen(
                viewModel = chatViewModel,
                conversationListViewModel = conversationListViewModel,
                onNavigateToServers = { navController.navigate(ServerConfig) },
                onNavigateToSettings = { navController.navigate(Settings) },
                onNavigateToSetup = { navController.navigate(Setup) },
                onConversationSelected = { id ->
                    if (id != null) {
                        navController.navigate(Chat(conversationId = id)) {
                            popUpTo<ConversationList> { inclusive = true }
                        }
                    } else {
                        navController.navigate(ConversationList) {
                            popUpTo<ConversationList> { inclusive = true }
                        }
                    }
                },
                conversationId = route.conversationId,
            )
        }
        composable<ServerConfig> {
            val serverViewModel = viewModel {
                ServerConfigViewModel(
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
            ServerConfigScreen(
                viewModel = serverViewModel,
                onNavigateBack = { navController.popBackStack() },
                onServerSaved = { navController.popBackStack() },
            )
        }
        composable<ServerEdit> { backStackEntry ->
            val serverViewModel = viewModel {
                ServerConfigViewModel(
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
            ServerConfigScreen(
                viewModel = serverViewModel,
                onNavigateBack = { navController.popBackStack() },
                onServerSaved = { navController.popBackStack() },
            )
        }
        composable<Settings> {
            val settingsViewModel = viewModel {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToServers = { navController.navigate(ServerConfig) },
                onNavigateToModels = { navController.navigate(ModelManagement) },
            )
        }
        composable<Setup> {
            SetupScreen(
                onChooseLocal = {
                    navController.navigate(ModelManagement) {
                        popUpTo<Setup> { inclusive = true }
                    }
                },
                onChooseServer = {
                    navController.navigate(ServerConfig) {
                        popUpTo<Setup> { inclusive = true }
                    }
                },
            )
        }
        composable<ModelManagement> {
            val modelManagementViewModel = viewModel {
                ModelManagementViewModel(
                    localModelStore = container.localModelStore,
                    downloadManager = container.modelDownloadManager,
                    llmEngine = container.llmEngine,
                    localLlmClient = container.localLlmClient,
                    modelsDir = container.modelsDir,
                    appContext = context.applicationContext as android.app.Application,
                    appScope = container.applicationScope,
                )
            }
            ModelManagementScreen(
                viewModel = modelManagementViewModel,
                onNavigateBack = { navController.popBackStack() },
                onBrowseHuggingFace = { navController.navigate(HuggingFaceBrowser) },
            )
        }
        composable<HuggingFaceBrowser> {
            val huggingFaceBrowserViewModel = viewModel {
                HuggingFaceBrowserViewModel(
                    client = container.huggingFaceClient,
                    encryptedDataStore = container.encryptedDataStore,
                    localModelStore = container.localModelStore,
                    downloadManager = container.modelDownloadManager,
                    appScope = container.applicationScope,
                )
            }
            HuggingFaceBrowserScreen(
                viewModel = huggingFaceBrowserViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
