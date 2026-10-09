package dev.nutting.pocketllm.ui.modelmanagement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.remote.huggingface.HfGgufFile
import dev.nutting.pocketllm.data.remote.huggingface.HfModelSummary
import dev.nutting.pocketllm.data.remote.huggingface.HfRepoListing
import dev.nutting.pocketllm.data.remote.huggingface.HuggingFace
import dev.nutting.pocketllm.util.deviceTotalRamMb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceBrowserScreen(
    viewModel: HuggingFaceBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTokenDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deviceRamMb = remember { deviceTotalRamMb(context) }

    BackHandler(enabled = state.repo != null || state.isLoadingRepo) { viewModel.closeRepo() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (state.showCellularWarning) {
        CellularWarningDialog(
            onConfirm = { viewModel.confirmCellularDownload() },
            onDismiss = { viewModel.dismissCellularWarning() },
        )
    }

    if (showTokenDialog) {
        TokenDialog(
            hasToken = state.hasToken,
            onSave = { viewModel.saveToken(it); showTokenDialog = false },
            onClear = { viewModel.clearToken(); showTokenDialog = false },
            onDismiss = { showTokenDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.repo?.repoId ?: "Hugging Face",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.closeRepo()) onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showTokenDialog = true }) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = "Access token",
                            tint = if (state.hasToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isSearching || state.isLoadingRepo) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val repo = state.repo
            if (repo != null) {
                RepoFiles(
                    repo = repo,
                    state = state,
                    deviceRamMb = deviceRamMb,
                    onSelectProjector = viewModel::selectProjector,
                    onDownload = viewModel::download,
                    estimateRam = { viewModel.buildEntry(it).minimumRamMb },
                )
            } else {
                SearchPane(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmit = viewModel::submitQuery,
                    onOpen = viewModel::openRepo,
                )
            }
        }
    }
}

@Composable
private fun SearchPane(
    state: HuggingFaceBrowserUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpen: (HfModelSummary) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        onSubmit()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            label = { Text("Search models or paste a link") },
            placeholder = { Text("e.g. qwen3, unsloth/gemma-3-1b-it-GGUF") },
            trailingIcon = {
                IconButton(onClick = submit, enabled = state.query.isNotBlank()) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )

        when {
            !state.hasSearched && !state.isSearching -> HintText(
                "Search Hugging Face for GGUF models, or paste a repo ID (owner/name) or a huggingface.co link to open it directly."
            )
            state.hasSearched && state.results.isEmpty() && !state.isSearching -> HintText(
                "No GGUF models found. Try a broader search term."
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.results, key = { it.id }) { model ->
                    SearchResultCard(model, onClick = { onOpen(model) })
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchResultCard(model: HfModelSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (model.isGated) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Gated",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(formatCount(model.downloads), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(formatCount(model.likes), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RepoFiles(
    repo: HfRepoListing,
    state: HuggingFaceBrowserUiState,
    deviceRamMb: Int,
    onSelectProjector: (String?) -> Unit,
    onDownload: (HfGgufFile) -> Unit,
    estimateRam: (HfGgufFile) -> Int,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.repoIsGated && !state.hasToken) {
            item {
                NoticeCard(
                    "This is a gated model. Accept its license on huggingface.co, then add an access token using the key icon above."
                )
            }
        }
        if (repo.skippedSplitFiles > 0) {
            item {
                HintCaption("${repo.skippedSplitFiles} multi-part GGUF files are hidden (not supported yet).")
            }
        }
        if (repo.projectorFiles.isNotEmpty()) {
            item {
                ProjectorSelector(
                    projectors = repo.projectorFiles,
                    selectedPath = state.selectedProjectorPath,
                    onSelect = onSelectProjector,
                )
            }
        }
        item {
            Text(
                "Model files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(repo.modelFiles, key = { it.path }) { file ->
            ModelFileCard(
                file = file,
                status = state.modelStatuses[HuggingFace.modelId(file.repoId, file.path)],
                highlighted = file.path == state.highlightedPath,
                requiredRamMb = estimateRam(file),
                deviceRamMb = deviceRamMb,
                onDownload = { onDownload(file) },
            )
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HintCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProjectorSelector(
    projectors: List<HfGgufFile>,
    selectedPath: String?,
    onSelect: (String?) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "Vision projector",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Downloaded alongside the model to enable image input.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ProjectorOption("None (text only)", selectedPath == null) { onSelect(null) }
            projectors.forEach { p ->
                ProjectorOption("${p.fileName} · ${formatSize(p.sizeBytes)}", selectedPath == p.path) { onSelect(p.path) }
            }
        }
    }
}

@Composable
private fun ProjectorOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelFileCard(
    file: HfGgufFile,
    status: DownloadStatus?,
    highlighted: Boolean,
    requiredRamMb: Int,
    deviceRamMb: Int,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlighted) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.quantization, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.bodySmall)
                if (deviceRamMb in 1 until requiredRamMb) {
                    Text(
                        "Needs ~${requiredRamMb}MB RAM (device has ${deviceRamMb}MB)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center) {
                when (status) {
                    DownloadStatus.COMPLETE -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    DownloadStatus.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    // FAILED downloads are retried from the Local Models screen
                    DownloadStatus.FAILED -> Text(
                        "Failed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    else -> Button(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenDialog(
    hasToken: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hugging Face Token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Only needed for gated or private models. Create a read token at huggingface.co/settings/tokens. " +
                        "It's stored encrypted on this device and only sent to huggingface.co.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text(if (hasToken) "Replace token" else "Token") },
                    placeholder = { Text("hf_...") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token) }, enabled = token.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasToken) {
                    TextButton(onClick = onClear) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}
