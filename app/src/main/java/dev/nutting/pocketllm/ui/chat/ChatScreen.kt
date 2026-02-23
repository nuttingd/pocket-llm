package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
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
    onNavigateToSetup: () -> Unit = {},
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

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        uri?.let { viewModel.exportConversationToFile(it, context) }
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Redirect to setup on first launch when no servers are configured and no local models
    LaunchedEffect(state.serversLoaded, state.availableServers.size, state.localModels.size) {
        if (state.serversLoaded && state.availableServers.isEmpty() && state.localModels.isEmpty()) {
            onNavigateToSetup()
        }
    }

    // Scroll to bottom when new messages arrive or streaming starts/stops.
    // With reverseLayout = true, index 0 is the bottom of the list.
    LaunchedEffect(state.messages.size, state.isStreaming) {
        listState.animateScrollToItem(0)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    val editingMessage = state.editingMessage
    if (editingMessage != null) {
        EditMessageDialog(
            originalContent = editingMessage.content,
            onConfirm = viewModel::confirmEditMessage,
            onDismiss = viewModel::cancelEditMessage,
        )
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
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
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
                                onSwitchToLocal = viewModel::switchToLocal,
                                onSwitchToRemote = viewModel::switchToRemote,
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
                            onSaveToFile = {
                                val title = state.conversationTitle.replace(Regex("[^a-zA-Z0-9 ]"), "").take(40)
                                saveFileLauncher.launch("$title.md")
                            },
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
                    modifier = Modifier,
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
                    onEdit = viewModel::startEditMessage,
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
        // Hide compacted messages and show compaction indicator
        val latestCompaction = state.compactionSummaries.maxByOrNull { it.createdAt }
        val compactedCount = latestCompaction?.compactedMessageCount ?: 0
        val hasCompaction = latestCompaction != null && compactedCount > 0 && compactedCount < state.messages.size
        val compactedMessages = if (hasCompaction) state.messages.take(compactedCount) else emptyList()
        val visibleMessages = if (hasCompaction) state.messages.drop(compactedCount) else state.messages
        var showCompactedMessages by remember { mutableStateOf(false) }

        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = modifier.fillMaxSize(),
        ) {
            // With reverseLayout, index 0 is at the bottom of the screen.
            // Items are laid out bottom-to-top, so we add newest content first.

            if (state.pendingToolCalls.isNotEmpty()) {
                items(state.pendingToolCalls.reversed(), key = { it.id }) { toolCall ->
                    ToolCallCard(
                        toolCall = toolCall,
                        status = state.toolCallResults[toolCall.id]?.let {
                            ToolCallStatus.Complete(it)
                        } ?: ToolCallStatus.Pending,
                        onApprove = onApproveToolCalls,
                        onDecline = onDeclineToolCalls,
                    )
                }
            }
            if (state.isCompacting) {
                item(key = "compacting") {
                    CompactingIndicator()
                }
            }
            if (state.isStreaming && state.currentStreamingContent.isNotEmpty()) {
                item(key = "streaming") {
                    StreamingMessageBubble(content = state.currentStreamingContent)
                }
            }
            items(visibleMessages.reversed(), key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    fontSizeSp = state.messageFontSizeSp,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
            if (hasCompaction) {
                item(key = "compaction-indicator") {
                    CompactionIndicator(
                        summary = latestCompaction!!,
                        showingCompactedMessages = showCompactedMessages,
                        onToggleCompactedMessages = { showCompactedMessages = !showCompactedMessages },
                    )
                }
                if (showCompactedMessages) {
                    items(compactedMessages.reversed(), key = { "compacted-${it.id}" }) { message ->
                        MessageBubble(
                            message = message,
                            fontSizeSp = state.messageFontSizeSp,
                            onCopy = onCopy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatOverflowMenu(
    onNavigateToSettings: () -> Unit,
    onCompact: () -> Unit,
    onSaveToFile: () -> Unit = {},
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
                text = { Text("Save to file") },
                onClick = {
                    expanded = false
                    onSaveToFile()
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
    onSwitchToLocal: (String) -> Unit = {},
    onSwitchToRemote: () -> Unit = {},
) {
    var showServerMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    val serverName = if (state.useLocalModel) {
        "Local"
    } else {
        state.selectedServer?.name ?: "No server"
    }
    val modelName = if (state.isLoadingModels) {
        "Loading..."
    } else {
        state.selectedModelId?.let { id ->
            if (state.useLocalModel) {
                state.localModels.find { it.id == id }?.name ?: id
            } else {
                id.substringAfterLast("/").ifBlank { id }
            }
        } ?: "No model"
    }

    Box {
        TextButton(
            onClick = {
                if (state.availableServers.size > 1 || state.localModels.isNotEmpty()) showServerMenu = true
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
            // Local models section
            state.localModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text("Local: ${model.name}") },
                    onClick = {
                        showServerMenu = false
                        onSwitchToLocal(model.id)
                    },
                )
            }
            // Remote servers
            if (state.localModels.isNotEmpty() && state.availableServers.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider()
            }
            state.availableServers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        showServerMenu = false
                        onSwitchToRemote()
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

@Composable
private fun EditMessageDialog(
    originalContent: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editedText by remember { mutableStateOf(originalContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 10,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(editedText.trim()) },
                enabled = editedText.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
