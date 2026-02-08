package dev.nutting.pocketllm.ui.modelmanagement

import android.Manifest
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    viewModel: ModelManagementViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmModelId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingDownloadEntry by remember { mutableStateOf<ModelRegistryEntry?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingDownloadEntry?.let { viewModel.downloadModel(it) }
        pendingDownloadEntry = null
    }

    fun onDownloadClicked(entry: ModelRegistryEntry) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingDownloadEntry = entry
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadModel(entry)
        }
    }

    var pendingModelUri by remember { mutableStateOf<Uri?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val projectorPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val modelUri = pendingModelUri
        if (uri == null || modelUri == null) {
            pendingModelUri = null
            return@rememberLauncherForActivityResult
        }
        isImporting = true
        scope.launch {
            viewModel.importModel(
                modelUri = modelUri,
                projectorUri = uri,
                modelFileName = getFileName(context, modelUri),
                projectorFileName = getFileName(context, uri),
            )
            pendingModelUri = null
            isImporting = false
            snackbarHostState.showSnackbar("Model imported successfully")
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingModelUri = uri
        projectorPicker.launch(arrayOf("*/*"))
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    if (state.showCellularWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCellularWarning() },
            title = { Text("Cellular Download") },
            text = { Text("You are on a cellular connection. This download may use significant mobile data. Continue?") },
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

    deleteConfirmModelId?.let { modelId ->
        AlertDialog(
            onDismissRequest = { deleteConfirmModelId = null },
            title = { Text("Delete Model") },
            text = { Text("This will delete the model files and free up storage. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteModel(modelId)
                    deleteConfirmModelId = null
                }) {
                    Text("Delete")
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
                title = { Text("On-Device Models") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Available Models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            val deviceRam = viewModel.getDeviceRamMb()

            items(state.registryModels) { entry ->
                val downloadedModel = state.downloadedModels.find { it.id == entry.id }
                val activeDownload = state.activeDownloads[entry.id]
                val isDownloading = activeDownload != null
                val isPartial = !isDownloading &&
                    downloadedModel?.downloadStatus == DownloadStatus.DOWNLOADING
                val isActive = state.activeModelId == entry.id
                val meetsRam = deviceRam >= entry.minimumRamMb

                RegistryModelCard(
                    entry = entry,
                    downloadedModel = downloadedModel,
                    isDownloading = isDownloading,
                    isPartialDownload = isPartial,
                    downloadProgress = activeDownload?.progress ?: 0f,
                    downloadBytesPerSec = activeDownload?.bytesPerSec ?: 0L,
                    isActive = isActive,
                    meetsRam = meetsRam,
                    onDownload = { onDownloadClicked(entry) },
                    onCancel = { viewModel.cancelDownload(entry.id) },
                    onDeletePartial = { viewModel.deletePartialDownload(entry.id) },
                    onSelect = { viewModel.selectModel(entry.id) },
                    onDelete = { deleteConfirmModelId = entry.id },
                )
            }

            val importedModels = state.downloadedModels.filter { model ->
                state.registryModels.none { it.id == model.id }
            }
            if (importedModels.isNotEmpty()) {
                item {
                    Text(
                        text = "Imported Models",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(importedModels) { model ->
                    ImportedModelCard(
                        model = model,
                        isActive = state.activeModelId == model.id,
                        onSelect = { viewModel.selectModel(model.id) },
                        onDelete = { deleteConfirmModelId = model.id },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { modelPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting,
                ) {
                    Text(if (isImporting) "Importing..." else "Import GGUF Model")
                }
                if (pendingModelUri != null && !isImporting) {
                    Text(
                        "Now select the vision projector GGUF file...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun RegistryModelCard(
    entry: ModelRegistryEntry,
    downloadedModel: LocalModel?,
    isDownloading: Boolean,
    isPartialDownload: Boolean,
    downloadProgress: Float,
    downloadBytesPerSec: Long,
    isActive: Boolean,
    meetsRam: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDeletePartial: () -> Unit,
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
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${entry.parameterCount} • ${entry.quantization} • ${formatSize(entry.totalSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    isDownloading -> {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel download",
                            )
                        }
                    }
                    isPartialDownload -> {
                        Row {
                            TextButton(onClick = onDownload) {
                                Text("Resume")
                            }
                            IconButton(onClick = onDeletePartial) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete partial download",
                                )
                            }
                        }
                    }
                    downloadedModel?.downloadStatus == DownloadStatus.COMPLETE -> {
                        Row {
                            if (isActive) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active model",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(12.dp),
                                )
                            } else {
                                TextButton(onClick = onSelect) {
                                    Text("Select")
                                }
                            }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                )
                            }
                        }
                    }
                    else -> {
                        IconButton(
                            onClick = onDownload,
                            enabled = meetsRam,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download model",
                            )
                        }
                    }
                }
            }

            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (!meetsRam && downloadedModel?.downloadStatus != DownloadStatus.COMPLETE) {
                Text(
                    text = "Requires ${entry.minimumRamMb / 1024} GB RAM (your device may not have enough)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                val rateText = formatRate(downloadBytesPerSec)
                Text(
                    text = "${(downloadProgress * 100).toInt()}%  •  $rateText",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (isPartialDownload && downloadedModel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val partialProgress = if (downloadedModel.totalSizeBytes > 0) {
                    downloadedModel.downloadedBytes.toFloat() / downloadedModel.totalSizeBytes
                } else 0f
                LinearProgressIndicator(
                    progress = { partialProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formatSize(downloadedModel.downloadedBytes)} / ${formatSize(downloadedModel.totalSizeBytes)}  •  Paused",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (downloadedModel?.downloadStatus == DownloadStatus.FAILED) {
                TextButton(onClick = onDownload) {
                    Text("Retry Download")
                }
            }
        }
    }
}

@Composable
private fun ImportedModelCard(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = model.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${model.parameterCount} • ${model.quantization} • Imported",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Active model",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    TextButton(onClick = onSelect) {
                        Text("Select")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete model",
                    )
                }
            }
        }
    }
}

private fun formatRate(bytesPerSec: Long): String {
    return when {
        bytesPerSec <= 0 -> "—"
        bytesPerSec < 1024 * 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) "%.1f GB".format(gb)
    else "%.0f MB".format(bytes / (1024.0 * 1024.0))
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) return it.getString(nameIndex)
        }
    }
    return uri.lastPathSegment ?: "imported-${System.currentTimeMillis()}.gguf"
}
