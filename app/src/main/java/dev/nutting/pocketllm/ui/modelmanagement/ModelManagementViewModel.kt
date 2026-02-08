package dev.nutting.pocketllm.ui.modelmanagement

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.local.model.ModelRegistry
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import dev.nutting.pocketllm.data.preferences.SettingsDataStore
import dev.nutting.pocketllm.util.ModelDownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ActiveDownload(val progress: Float = 0f, val bytesPerSec: Long = 0L)

data class ModelManagementUiState(
    val registryModels: List<ModelRegistryEntry> = emptyList(),
    val downloadedModels: List<LocalModel> = emptyList(),
    val activeModelId: String? = null,
    val activeDownloads: Map<String, ActiveDownload> = emptyMap(),
    val errorMessage: String? = null,
    val showCellularWarning: Boolean = false,
    val pendingDownloadEntry: ModelRegistryEntry? = null,
)

class ModelManagementViewModel(
    private val localModelStore: LocalModelStore,
    private val settingsDataStore: SettingsDataStore,
    private val modelsDir: File,
    private val appContext: Application,
) : ViewModel() {

    companion object {
        private const val TAG = "ModelManagementVM"

        // GGUF magic: bytes 'G' 'G' 'U' 'F' = 0x46554747 little-endian
        private const val GGUF_MAGIC = 0x46554747

        fun isValidGguf(file: File): Boolean {
            if (file.length() < 4) return false
            return try {
                file.inputStream().use { stream ->
                    val bytes = ByteArray(4)
                    if (stream.read(bytes) != 4) return false
                    val magic = (bytes[0].toInt() and 0xFF) or
                        ((bytes[1].toInt() and 0xFF) shl 8) or
                        ((bytes[2].toInt() and 0xFF) shl 16) or
                        ((bytes[3].toInt() and 0xFF) shl 24)
                    magic == GGUF_MAGIC
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val _uiState = MutableStateFlow(ModelManagementUiState())
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()

    private val workManager = WorkManager.getInstance(appContext)

    init {
        _uiState.update { it.copy(registryModels = ModelRegistry.entries) }

        viewModelScope.launch {
            localModelStore.models.collect { models ->
                _uiState.update { it.copy(downloadedModels = models) }
            }
        }

        viewModelScope.launch {
            settingsDataStore.getActiveLocalModelId().collect { id ->
                _uiState.update { it.copy(activeModelId = id.takeIf { it.isNotEmpty() }) }
            }
        }
    }

    fun downloadModel(entry: ModelRegistryEntry) {
        if (!checkStorage(entry.totalSizeBytes)) {
            _uiState.update { it.copy(errorMessage = "Not enough storage. Need ${formatSize(entry.totalSizeBytes)} free.") }
            return
        }

        if (isCellular()) {
            _uiState.update { it.copy(showCellularWarning = true, pendingDownloadEntry = entry) }
            return
        }

        startDownload(entry)
    }

    fun confirmCellularDownload() {
        val entry = _uiState.value.pendingDownloadEntry ?: return
        _uiState.update { it.copy(showCellularWarning = false, pendingDownloadEntry = null) }
        startDownload(entry)
    }

    fun dismissCellularWarning() {
        _uiState.update { it.copy(showCellularWarning = false, pendingDownloadEntry = null) }
    }

    private fun startDownload(entry: ModelRegistryEntry) {
        viewModelScope.launch {
            val existing = localModelStore.getById(entry.id)
            if (existing == null) {
                val model = LocalModel(
                    id = entry.id,
                    name = entry.name,
                    parameterCount = entry.parameterCount,
                    quantization = entry.quantization,
                    modelFileName = entry.modelFileName,
                    projectorFileName = entry.projectorFileName ?: "",
                    modelSizeBytes = entry.modelSizeBytes,
                    projectorSizeBytes = entry.projectorSizeBytes,
                    downloadStatus = DownloadStatus.DOWNLOADING,
                    sourceUrl = entry.modelDownloadUrl,
                    projectorSourceUrl = entry.projectorDownloadUrl,
                    minimumRamMb = entry.minimumRamMb,
                )
                localModelStore.save(model)
            } else {
                localModelStore.updateStatus(entry.id, DownloadStatus.DOWNLOADING)
            }
            _uiState.update { state ->
                state.copy(activeDownloads = state.activeDownloads + (entry.id to ActiveDownload()))
            }

            val notificationId = entry.id.hashCode().and(0x7FFFFFFF) % 10000 + 1001

            val workDataPairs = mutableListOf<Pair<String, Any>>(
                ModelDownloadWorker.KEY_MODEL_ID to entry.id,
                ModelDownloadWorker.KEY_MODEL_URL to entry.modelDownloadUrl,
                ModelDownloadWorker.KEY_MODEL_FILE_NAME to entry.modelFileName,
                ModelDownloadWorker.KEY_MODEL_SIZE_BYTES to entry.modelSizeBytes,
                ModelDownloadWorker.KEY_PROJECTOR_SIZE_BYTES to entry.projectorSizeBytes,
                ModelDownloadWorker.KEY_NOTIFICATION_ID to notificationId,
            )
            if (entry.hasProjector) {
                workDataPairs += ModelDownloadWorker.KEY_PROJECTOR_URL to entry.projectorDownloadUrl!!
                workDataPairs += ModelDownloadWorker.KEY_PROJECTOR_FILE_NAME to entry.projectorFileName!!
            }
            val workData = workDataOf(*workDataPairs.toTypedArray())

            val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workData)
                .addTag("model_download_${entry.id}")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                "model_download_${entry.id}",
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )

            workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo == null) return@collect
                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
                        val bytesPerSec = workInfo.progress.getLong(ModelDownloadWorker.KEY_BYTES_PER_SEC, 0L)
                        _uiState.update { state ->
                            state.copy(
                                activeDownloads = state.activeDownloads + (entry.id to ActiveDownload(progress, bytesPerSec)),
                            )
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update { state ->
                            state.copy(activeDownloads = state.activeDownloads - entry.id)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        _uiState.update { state ->
                            state.copy(
                                activeDownloads = state.activeDownloads - entry.id,
                                errorMessage = "Download failed. Check storage and network.",
                            )
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { state ->
                            state.copy(activeDownloads = state.activeDownloads - entry.id)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork("model_download_$modelId")
        viewModelScope.launch {
            localModelStore.updateStatus(modelId, DownloadStatus.NOT_DOWNLOADED)
        }
        _uiState.update { state ->
            state.copy(activeDownloads = state.activeDownloads - modelId)
        }
    }

    fun deletePartialDownload(modelId: String) {
        workManager.cancelUniqueWork("model_download_$modelId")
        viewModelScope.launch {
            val model = localModelStore.getById(modelId) ?: return@launch
            File(modelsDir, model.modelFileName).delete()
            if (model.projectorFileName.isNotEmpty()) {
                File(modelsDir, model.projectorFileName).delete()
            }
            localModelStore.delete(modelId)
        }
        _uiState.update { state ->
            state.copy(activeDownloads = state.activeDownloads - modelId)
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            settingsDataStore.setActiveLocalModelId(modelId)
            settingsDataStore.setInferenceProviderType("local")
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val model = localModelStore.getById(modelId) ?: return@launch

            File(modelsDir, model.modelFileName).delete()
            if (model.projectorFileName.isNotEmpty()) {
                File(modelsDir, model.projectorFileName).delete()
            }

            localModelStore.delete(modelId)

            // If deleting the active model, reset to remote
            val activeId = settingsDataStore.getActiveLocalModelId().first()
            if (activeId == modelId) {
                settingsDataStore.setActiveLocalModelId("")
                settingsDataStore.setInferenceProviderType("remote")
            }
        }
    }

    suspend fun importModel(
        modelUri: android.net.Uri,
        modelFileName: String,
    ) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val modelFile = File(modelsDir, modelFileName)

            appContext.contentResolver.openInputStream(modelUri)?.use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }

            if (!isValidGguf(modelFile)) {
                modelFile.delete()
                _uiState.update { it.copy(errorMessage = "Invalid GGUF file. Import cancelled.") }
                return@withContext
            }

            val id = "imported-${System.currentTimeMillis()}"
            val model = LocalModel(
                id = id,
                name = modelFileName.removeSuffix(".gguf"),
                parameterCount = "Unknown",
                quantization = "Unknown",
                modelFileName = modelFileName,
                modelSizeBytes = modelFile.length(),
                downloadStatus = DownloadStatus.COMPLETE,
                downloadedBytes = modelFile.length(),
                isImported = true,
            )
            localModelStore.save(model)
            settingsDataStore.setActiveLocalModelId(id)
            settingsDataStore.setInferenceProviderType("local")
            Log.i(TAG, "Model imported: $modelFileName")
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun getDeviceRamMb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    private fun checkStorage(requiredBytes: Long): Boolean {
        val stat = StatFs(modelsDir.path)
        return stat.availableBytes > requiredBytes + 100_000_000 // 100 MB buffer
    }

    private fun isCellular(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB".format(gb)
        else "%.0f MB".format(bytes / (1024.0 * 1024.0))
    }
}
