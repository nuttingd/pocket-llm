package dev.nutting.pocketllm.ui.modelmanagement

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import dev.nutting.pocketllm.llm.LlmEngine
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    viewModel: ModelManagementViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmModelId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    // Show errors via snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Cellular warning dialog
    if (state.showCellularWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCellularWarning() },
            title = { Text("Download on Cellular?") },
            text = { Text("You're not on Wi-Fi. This download may use significant mobile data. Continue?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCellularDownload() }) {
                    Text("Download Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCellularWarning() }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete confirmation dialog
    deleteConfirmModelId?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteConfirmModelId = null },
            title = { Text("Delete Model?") },
            text = { Text("This will delete the model file from your device. You can re-download it later.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(modelId)
                    deleteConfirmModelId = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmModelId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local Models") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Engine status
            EngineStatusCard(state)

            // GPU offload slider
            GpuOffloadCard(state.gpuOffloadPercent) { viewModel.updateGpuOffloadPercent(it) }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import GGUF")
                }
                if (state.engineState is LlmEngine.State.Ready || state.engineState is LlmEngine.State.Inferring) {
                    OutlinedButton(
                        onClick = { viewModel.unloadModel() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Unload Model")
                    }
                }
            }

            // Downloaded models
            val downloadedModels = state.downloadedModels.filter {
                it.downloadStatus == DownloadStatus.COMPLETE
            }
            if (downloadedModels.isNotEmpty()) {
                Text("Downloaded Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                downloadedModels.forEach { model ->
                    DownloadedModelCard(
                        model = model,
                        isActive = model.id == state.activeModelId,
                        onSelect = { viewModel.selectModel(model.id) },
                        onDelete = { deleteConfirmModelId = model.id },
                    )
                }
            }

            // In-progress / failed downloads
            val activeDownloads = state.downloadedModels.filter {
                it.downloadStatus == DownloadStatus.DOWNLOADING || it.downloadStatus == DownloadStatus.FAILED
            }
            if (activeDownloads.isNotEmpty()) {
                Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                activeDownloads.forEach { model ->
                    DownloadProgressCard(
                        model = model,
                        onCancel = { viewModel.cancelDownload(model.id) },
                        onRetry = {
                            viewModel.deletePartialDownload(model.id)
                            val entry = state.registryModels.find { it.id == model.id }
                            entry?.let { viewModel.downloadModel(it) }
                        },
                        onDelete = { viewModel.deletePartialDownload(model.id) },
                    )
                }
            }

            // Available models from registry
            val downloadedIds = state.downloadedModels.map { it.id }.toSet()
            val availableModels = state.registryModels.filter { it.id !in downloadedIds }
            if (availableModels.isNotEmpty()) {
                Text("Available Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val deviceRamMb = remember { viewModel.getDeviceRamMb() }
                availableModels.forEach { entry ->
                    RegistryModelCard(
                        entry = entry,
                        deviceRamMb = deviceRamMb,
                        onDownload = { viewModel.downloadModel(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(state: ModelManagementUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Engine Status", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            val statusText = when (state.engineState) {
                is LlmEngine.State.Unloaded -> "No model loaded"
                is LlmEngine.State.Loading -> "Loading model..."
                is LlmEngine.State.Ready -> "Ready"
                is LlmEngine.State.Inferring -> "Generating..."
                is LlmEngine.State.Error -> "Error: ${(state.engineState as LlmEngine.State.Error).message}"
            }
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            if (state.deviceInfo.isNotBlank()) {
                Text(state.deviceInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GpuOffloadCard(gpuPercent: Int, onUpdate: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("GPU Offload: $gpuPercent%", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Higher values use more GPU memory but run faster", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = gpuPercent.toFloat(),
                onValueChange = { onUpdate(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 9,
            )
        }
    }
}

@Composable
private fun DownloadedModelCard(
    model: LocalModel,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${model.parameterCount} / ${model.quantization} / ${model.totalSizeBytes / (1024 * 1024)}MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    if (isActive) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        TextButton(onClick = onSelect) {
                            Text("Select")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    model: LocalModel,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (model.downloadStatus == DownloadStatus.DOWNLOADING) {
                val total = model.totalSizeBytes
                val progress = if (total > 0) {
                    model.downloadedBytes.toFloat() / total
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${model.downloadedBytes / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                Text("Download failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun RegistryModelCard(
    entry: ModelRegistryEntry,
    deviceRamMb: Int,
    onDownload: () -> Unit,
) {
    val meetsRam = deviceRamMb >= entry.minimumRamMb
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${entry.parameterCount} / ${entry.quantization} / ${entry.totalSizeBytes / (1024 * 1024)}MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!meetsRam) {
                        Text(
                            "Requires ${entry.minimumRamMb}MB RAM (device has ${deviceRamMb}MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            }
        }
    }
}
