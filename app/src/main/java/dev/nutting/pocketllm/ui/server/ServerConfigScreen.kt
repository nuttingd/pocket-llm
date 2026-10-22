package dev.nutting.pocketllm.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerKind

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
                    onKindChange = viewModel::setKind,
                    onSave = {
                        viewModel.saveServer()
                        onServerSaved()
                    },
                    onCancel = viewModel::cancelEdit,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val sections = listOf(
                        Triple("Chat servers", "Chat with models through the OpenAI chat API (Ollama, LM Studio, OpenAI…)", state.chatServers),
                        Triple("Image servers", "Generate and edit images (stable-diffusion.cpp sd-server, OpenAI images API)", state.imageServers),
                    )
                    sections.forEach { (title, subtitle, servers) ->
                        item(key = title) { SectionHeader(title, subtitle, isEmpty = servers.isEmpty()) }
                        items(servers, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                imageDescription = state.imageServerDescriptions[server.id],
                                onEdit = { viewModel.startEditServer(server) },
                                onDelete = { viewModel.deleteServer(server.id) },
                                onTest = { viewModel.validateAndFetchModels(server.id) },
                                isTesting = state.isTesting,
                                testResult = state.testResult,
                                fetchedModels = state.models.map { it.id },
                                selectedModelId = state.selectedModelId,
                                onModelSelect = { viewModel.selectModel(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, isEmpty: Boolean) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() }) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isEmpty) {
            Text("None yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(
    server: dev.nutting.pocketllm.data.local.entity.ServerProfileEntity,
    imageDescription: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean = false,
    testResult: String? = null,
    fetchedModels: List<String> = emptyList(),
    selectedModelId: String? = null,
    onModelSelect: (String) -> Unit = {},
) {
    val isImage = server.kind == ServerKind.IMAGE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${if (isImage) "Image server" else "Chat server"}: ${server.name}" },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isImage) Icons.Default.Image else Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(server.name, style = MaterialTheme.typography.titleMedium)
            }
            Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isImage) {
                val model = server.imageModelId?.let { " · model $it" }.orEmpty()
                Text(
                    (imageDescription ?: "Image server") + model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            if (fetchedModels.isNotEmpty() && !isImage) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fetchedModels.forEach { modelId ->
                        FilterChip(
                            selected = modelId == selectedModelId,
                            onClick = { onModelSelect(modelId) },
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
    onKindChange: (ServerKind) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text("Server type", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val kinds = listOf(ServerKind.CHAT to "Chat", ServerKind.IMAGE to "Image generation")
            kinds.forEachIndexed { index, (kind, label) ->
                SegmentedButton(
                    selected = editing.kind == kind,
                    onClick = { onKindChange(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    icon = { Icon(if (kind == ServerKind.IMAGE) Icons.Default.Image else Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) { Text(label) }
            }
        }
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
            placeholder = { Text(if (editing.kind == ServerKind.IMAGE) "http://192.168.1.10:8080" else "http://localhost:11434") },
            supportingText = { DetectionText(editing) },
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
        if (editing.kind == ServerKind.IMAGE && editing.needsImageModel) {
            OutlinedTextField(
                value = editing.imageModelId,
                onValueChange = { onUpdate(editing.copy(imageModelId = it)) },
                label = { Text("Image model") },
                placeholder = { Text("e.g. gpt-image-1") },
                supportingText = { Text("The model this server should generate images with") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (editing.suggestedModels.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    editing.suggestedModels.forEach { model ->
                        FilterChip(
                            selected = model == editing.imageModelId,
                            onClick = { onUpdate(editing.copy(imageModelId = model)) },
                            label = { Text(model.substringAfterLast("/").ifBlank { model }, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
        if (editing.kind == ServerKind.CHAT) OutlinedTextField(
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
        if (editing.kind == ServerKind.IMAGE) OutlinedTextField(
            value = editing.imageTimeoutMinutes.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { value ->
                onUpdate(editing.copy(imageTimeoutMinutes = value.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0))
            },
            label = { Text("Image generation timeout") },
            suffix = { Text("minutes") },
            supportingText = { Text("How long one image may take before it's given up on") },
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

/** What probing the URL found, under the URL field. */
@Composable
private fun DetectionText(editing: EditingServer) {
    val detection = editing.detection
    val isImage = editing.kind == ServerKind.IMAGE
    val text = when (detection) {
        Detection.Idle -> return
        Detection.Checking -> "Checking the server…"
        is Detection.Unreachable -> "Couldn't reach the server: ${detection.message}"
        is Detection.Found -> when {
            detection.info.dialect == ImageDialect.SD_CPP && isImage -> "Detected ${detection.info.description}"
            detection.info.dialect == ImageDialect.SD_CPP -> "This is a stable-diffusion.cpp image server; it can't chat. Choose Image generation."
            isImage -> "Not stable-diffusion.cpp; images will be requested through the OpenAI images API"
            else -> "Reachable"
        }
    }
    val warn = detection is Detection.Unreachable || (detection is Detection.Found && detection.info.dialect == ImageDialect.SD_CPP && !isImage)
    Text(text, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

