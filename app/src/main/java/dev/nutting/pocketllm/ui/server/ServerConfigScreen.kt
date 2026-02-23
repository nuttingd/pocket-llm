package dev.nutting.pocketllm.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    viewModel: ServerConfigViewModel,
    onNavigateBack: () -> Unit,
    onServerSaved: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Servers") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.editingServer == null) {
                FloatingActionButton(
                    onClick = { viewModel.startAddServer() },
                    modifier = Modifier.semantics { contentDescription = "Add server" },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            if (state.editingServer != null) {
                ServerEditForm(
                    editing = state.editingServer!!,
                    isLoading = state.isLoading,
                    onUpdate = viewModel::updateEditingServer,
                    onSave = {
                        viewModel.saveServer()
                        onServerSaved()
                    },
                    onCancel = viewModel::cancelEdit,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            onEdit = { viewModel.startEditServer(server) },
                            onDelete = { viewModel.deleteServer(server.id) },
                            onTest = { viewModel.validateAndFetchModels(server.id) },
                            isTesting = state.isTesting,
                            testResult = state.testResult,
                            fetchedModels = state.models.map { it.id },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(
    server: dev.nutting.pocketllm.data.local.entity.ServerProfileEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean = false,
    testResult: String? = null,
    fetchedModels: List<String> = emptyList(),
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Server: ${server.name}" },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium)
            Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (server.hasApiKey) {
                Text("API key configured", style = MaterialTheme.typography.bodySmall)
            }
            testResult?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (fetchedModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fetchedModels.forEach { modelId ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    modelId.substringAfterLast("/").ifBlank { modelId },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTest, enabled = !isTesting) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Testing...")
                    } else {
                        Text("Test")
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics { contentDescription = "Edit ${server.name}" },
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete ${server.name}" },
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun ServerEditForm(
    editing: EditingServer,
    isLoading: Boolean,
    onUpdate: (EditingServer) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = editing.name,
            onValueChange = { onUpdate(editing.copy(name = it)) },
            label = { Text("Server Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = editing.baseUrl,
            onValueChange = { onUpdate(editing.copy(baseUrl = it)) },
            label = { Text("Base URL") },
            placeholder = { Text("http://localhost:11434") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = editing.apiKey,
            onValueChange = { onUpdate(editing.copy(apiKey = it)) },
            label = { Text("API Key (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = editing.requestTimeoutSeconds.toString(),
            onValueChange = { value ->
                val seconds = value.filter { it.isDigit() }.toIntOrNull()
                if (seconds != null) {
                    onUpdate(editing.copy(requestTimeoutSeconds = seconds))
                } else if (value.isEmpty()) {
                    onUpdate(editing.copy(requestTimeoutSeconds = 0))
                }
            },
            label = { Text("Request Timeout") },
            suffix = { Text("seconds") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSave,
                enabled = !isLoading,
                modifier = Modifier.semantics { contentDescription = "Save server" },
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Save")
            }
        }
    }
}
