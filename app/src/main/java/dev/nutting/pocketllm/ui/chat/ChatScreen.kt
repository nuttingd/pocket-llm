package dev.nutting.pocketllm.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToServers: () -> Unit,
    conversationId: String?,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Auto-scroll when new messages arrive or during streaming
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.conversationTitle) },
                actions = {
                    IconButton(
                        onClick = onNavigateToServers,
                        modifier = Modifier.semantics { contentDescription = "Server settings" },
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                    }
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.messages.isEmpty() && !state.isStreaming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
                if (state.isStreaming && state.currentStreamingContent.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingMessageBubble(content = state.currentStreamingContent)
                    }
                }
            }
        }
    }
}
