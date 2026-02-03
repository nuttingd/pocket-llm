package dev.nutting.pocketllm.ui.navigation

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
import dev.nutting.pocketllm.ui.server.ServerConfigScreen
import dev.nutting.pocketllm.ui.server.ServerConfigViewModel

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PocketLlmApplication).container }

    NavHost(
        navController = navController,
        startDestination = ConversationList,
        modifier = modifier,
    ) {
        composable<ConversationList> {
            // For MVP, go directly to chat
            val chatViewModel = remember {
                ChatViewModel(
                    chatManager = container.chatManager,
                    conversationRepository = container.conversationRepository,
                    messageRepository = container.messageRepository,
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToServers = { navController.navigate(ServerConfig) },
                conversationId = null,
            )
        }
        composable<Chat> { backStackEntry ->
            val route = backStackEntry.toRoute<Chat>()
            val chatViewModel = remember {
                ChatViewModel(
                    chatManager = container.chatManager,
                    conversationRepository = container.conversationRepository,
                    messageRepository = container.messageRepository,
                    serverRepository = container.serverRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToServers = { navController.navigate(ServerConfig) },
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
            val route = backStackEntry.toRoute<ServerEdit>()
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
            // TODO: SettingsScreen
        }
    }
}
