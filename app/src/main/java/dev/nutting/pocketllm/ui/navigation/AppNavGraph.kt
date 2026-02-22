package dev.nutting.pocketllm.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.nutting.pocketllm.PocketLlmApplication
import dev.nutting.pocketllm.ui.chat.ChatScreen
import dev.nutting.pocketllm.ui.chat.ChatViewModel
import dev.nutting.pocketllm.ui.conversations.ConversationListViewModel
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

    val conversationListViewModel = remember {
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
            val chatViewModel = remember {
                ChatViewModel(
                    chatManager = container.chatManager,
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
                onNavigateToModels = { navController.navigate(ModelManagement) },
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
            val chatViewModel = remember(route.conversationId) {
                ChatViewModel(
                    chatManager = container.chatManager,
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
                onNavigateToModels = { navController.navigate(ModelManagement) },
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
            val serverViewModel = remember {
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
            val serverViewModel = remember {
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
            val settingsViewModel = remember {
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
                onChooseLocal = { navController.navigate(ModelManagement) },
                onChooseServer = { navController.navigate(ServerConfig) },
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable<ModelManagement> {
            val modelManagementViewModel = remember {
                ModelManagementViewModel(
                    localModelStore = container.localModelStore,
                    llmEngine = container.llmEngine,
                    modelsDir = container.modelsDir,
                    appContext = context.applicationContext as android.app.Application,
                )
            }
            ModelManagementScreen(
                viewModel = modelManagementViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
