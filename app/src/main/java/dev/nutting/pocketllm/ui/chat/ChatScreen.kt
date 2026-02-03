package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.ui.conversations.ConversationListDrawerContent
import dev.nutting.pocketllm.ui.conversations.ConversationListViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversationListViewModel: ConversationListViewModel,
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onConversationSelected: (String?) -> Unit,
    conversationId: String?,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(state.messages.size, state.currentStreamingContent) {
        val totalItems = state.messages.size + if (state.isStreaming) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    if (state.showConversationSettings) {
        ConversationSettingsSheet(
            params = state.conversationParams,
            defaults = state.defaultParams,
            onParamsChanged = viewModel::updateConversationParams,
            onResetToDefaults = viewModel::resetConversationParamsToDefaults,
            onDismiss = viewModel::dismissConversationSettings,
            availableTools = state.availableTools,
            onToggleTool = viewModel::toggleTool,
            presets = state.presets,
            onApplyPreset = viewModel::applyPreset,
            onSaveAsPreset = viewModel::saveAsPreset,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConversationListDrawerContent(
                    viewModel = conversationListViewModel,
                    onConversationSelected = { id ->
                        scope.launch { drawerState.close() }
                        onConversationSelected(id)
                    },
                    onNewChat = {
                        scope.launch { drawerState.close() }
                        onConversationSelected(null)
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics { contentDescription = "Open conversations" },
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = null)
                        }
                    },
                    title = {
                        Column {
                            Text(
                                state.conversationTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ServerModelSelector(
                                state = state,
                                onSwitchServer = viewModel::switchServer,
                                onSwitchModel = viewModel::switchModel,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = viewModel::toggleConversationSettings,
                            modifier = Modifier.semantics { contentDescription = "Conversation parameters" },
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                        }
                        ChatOverflowMenu(
                            onNavigateToSettings = onNavigateToSettings,
                            onCompact = viewModel::compactConversation,
                            onShare = {
                                scope.launch {
                                    val markdown = viewModel.exportConversation()
                                    if (markdown != null) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, markdown)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share conversation"))
                                    }
                                }
                            },
                        )
                    },
                )
            },
            bottomBar = {
                MessageInput(
                    isStreaming = state.isStreaming,
                    onSendMessage = viewModel::sendMessage,
                    onStopGeneration = viewModel::stopGeneration,
                    modifier = Modifier
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    onSendMessageWithImages = { text, uris ->
                        viewModel.sendMessageWithImages(text, uris, context)
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.estimatedTokensUsed > 0) {
                    ContextUsageBar(state = state)
                }
                ChatContent(
                    state = state,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                    },
                    onRegenerate = viewModel::regenerateMessage,
                    onDelete = viewModel::deleteMessage,
                    onApproveToolCalls = viewModel::approveToolCalls,
                    onDeclineToolCalls = viewModel::declineToolCalls,
                )
            }
        }
    }
}

@Composable
private fun ContextUsageBar(state: ChatUiState) {
    val maxTokens = state.conversationParams.maxTokens
        ?: state.defaultParams.maxTokens
        ?: 2048
    // Use a generous context window estimate (4x max tokens or at least 8192)
    val contextWindow = maxOf(maxTokens * 4, 8192)
    val ratio = (state.estimatedTokensUsed.toFloat() / contextWindow).coerceIn(0f, 1f)
    val thresholdRatio = state.compactionThresholdPct / 100f

    val color = when {
        ratio > thresholdRatio -> MaterialTheme.colorScheme.error
        ratio > 0.5f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    LinearProgressIndicator(
        progress = { ratio },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun ChatContent(
    state: ChatUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
    onRegenerate: (dev.nutting.pocketllm.data.local.entity.MessageEntity) -> Unit = {},
    onEdit: ((dev.nutting.pocketllm.data.local.entity.MessageEntity) -> Unit)? = null,
    onDelete: (dev.nutting.pocketllm.data.local.entity.MessageEntity) -> Unit = {},
    onApproveToolCalls: (() -> Unit)? = null,
    onDeclineToolCalls: (() -> Unit)? = null,
) {
    if (state.messages.isEmpty() && !state.isStreaming) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Start a conversation",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    fontSizeSp = state.messageFontSizeSp,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
            if (state.isStreaming && state.currentStreamingContent.isNotEmpty()) {
                item(key = "streaming") {
                    StreamingMessageBubble(content = state.currentStreamingContent)
                }
            }
            if (state.pendingToolCalls.isNotEmpty()) {
                items(state.pendingToolCalls, key = { it.id }) { toolCall ->
                    ToolCallCard(
                        toolCall = toolCall,
                        status = state.toolCallResults[toolCall.function.name]?.let {
                            ToolCallStatus.Complete(it)
                        } ?: ToolCallStatus.Pending,
                        onApprove = onApproveToolCalls,
                        onDecline = onDeclineToolCalls,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatOverflowMenu(
    onNavigateToSettings: () -> Unit,
    onCompact: () -> Unit,
    onShare: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "More options" },
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Share conversation") },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text("Compact conversation") },
                onClick = {
                    expanded = false
                    onCompact()
                },
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    expanded = false
                    onNavigateToSettings()
                },
            )
        }
    }
}

@Composable
private fun ServerModelSelector(
    state: ChatUiState,
    onSwitchServer: (String) -> Unit,
    onSwitchModel: (String) -> Unit,
) {
    var showServerMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    val serverName = state.selectedServer?.name ?: "No server"
    val modelName = if (state.isLoadingModels) {
        "Loading..."
    } else {
        state.selectedModelId?.let { id ->
            id.substringAfterLast("/").ifBlank { id }
        } ?: "No model"
    }

    Box {
        TextButton(
            onClick = {
                if (state.availableServers.size > 1) showServerMenu = true
                else showModelMenu = true
            },
            modifier = Modifier.semantics { contentDescription = "Switch server or model" },
        ) {
            if (state.isLoadingModels) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp).padding(end = 4.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Text(
                "$serverName · $modelName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DropdownMenu(expanded = showServerMenu, onDismissRequest = { showServerMenu = false }) {
            state.availableServers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        showServerMenu = false
                        onSwitchServer(server.id)
                    },
                )
            }
        }

        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
            state.availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.id) },
                    onClick = {
                        showModelMenu = false
                        onSwitchModel(model.id)
                    },
                )
            }
        }
    }
}
