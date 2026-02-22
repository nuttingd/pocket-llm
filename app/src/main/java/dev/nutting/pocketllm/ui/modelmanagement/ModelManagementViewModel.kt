package dev.nutting.pocketllm.ui.modelmanagement

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.local.model.ModelRegistry
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import dev.nutting.pocketllm.llm.LlmEngine
import dev.nutting.pocketllm.util.ModelDownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

data class ModelManagementUiState(
    val registryModels: List<ModelRegistryEntry> = emptyList(),
    val downloadedModels: List<LocalModel> = emptyList(),
    val activeModelId: String? = null,
    val gpuOffloadPercent: Int = 80,
    val engineState: LlmEngine.State = LlmEngine.State.Unloaded,
    val deviceInfo: String = "",
    val errorMessage: String? = null,
    val showCellularWarning: Boolean = false,
    val pendingDownloadEntry: ModelRegistryEntry? = null,
)

class ModelManagementViewModel(
    private val localModelStore: LocalModelStore,
    private val llmEngine: LlmEngine,
    private val modelsDir: File,
    private val appContext: Application,
) : ViewModel() {

    companion object {
        private const val TAG = "ModelManagementVM"
        private const val GGUF_MAGIC = 0x46554747

        fun isValidGguf(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = raf.readInt().let { Integer.reverseBytes(it) }
                    magic == GGUF_MAGIC
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val _uiState = MutableStateFlow(ModelManagementUiState(
        registryModels = ModelRegistry.entries,
    ))
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()

    init {
        observeModels()
        observeEngineState()
    }

    private fun observeModels() {
        viewModelScope.launch {
            combine(
                localModelStore.models,
                localModelStore.activeModelId,
                localModelStore.gpuOffloadPercent,
            ) { models, activeId, gpuPercent ->
                Triple(models, activeId, gpuPercent)
            }.collect { (models, activeId, gpuPercent) ->
                _uiState.update {
                    it.copy(
                        downloadedModels = models,
                        activeModelId = activeId,
                        gpuOffloadPercent = gpuPercent,
                    )
                }
            }
        }
    }

    private fun observeEngineState() {
        viewModelScope.launch {
            llmEngine.state.collect { state ->
                _uiState.update {
                    it.copy(
                        engineState = state,
                        deviceInfo = llmEngine.deviceInfo,
                    )
                }
            }
        }
    }

    fun downloadModel(entry: ModelRegistryEntry) {
        // Check available storage
        val stat = StatFs(modelsDir.absolutePath)
        val availableBytes = stat.availableBytes
        val requiredBytes = entry.modelSizeBytes + 100_000_000L // 100 MB buffer
        if (availableBytes < requiredBytes) {
            _uiState.update { it.copy(errorMessage = "Not enough storage. Need ${requiredBytes / (1024 * 1024)}MB, have ${availableBytes / (1024 * 1024)}MB.") }
            return
        }

        // Check if on cellular
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
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
            // Create model entry in store
            val model = LocalModel(
                id = entry.id,
                name = entry.name,
                parameterCount = entry.parameterCount,
                quantization = entry.quantization,
                modelFileName = entry.modelFileName,
                modelSizeBytes = entry.modelSizeBytes,
                downloadStatus = DownloadStatus.DOWNLOADING,
                sourceUrl = entry.modelDownloadUrl,
            )
            localModelStore.save(model)

            // Enqueue WorkManager job
            val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(
                    ModelDownloadWorker.KEY_MODEL_ID to entry.id,
                    ModelDownloadWorker.KEY_MODEL_URL to entry.modelDownloadUrl,
                    ModelDownloadWorker.KEY_MODEL_FILENAME to entry.modelFileName,
                    ModelDownloadWorker.KEY_TOTAL_SIZE to entry.modelSizeBytes,
                ))
                .build()

            WorkManager.getInstance(appContext)
                .enqueueUniqueWork("download_${entry.id}", ExistingWorkPolicy.KEEP, workRequest)

            Log.i(TAG, "Download enqueued for ${entry.id}")
        }
    }

    fun cancelDownload(modelId: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork("download_$modelId")
        viewModelScope.launch {
            localModelStore.updateStatus(modelId, DownloadStatus.FAILED)
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            localModelStore.setActiveModelId(modelId)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val model = localModelStore.getById(modelId)
            if (model != null) {
                val file = File(modelsDir, model.modelFileName)
                if (file.exists()) file.delete()
            }
            localModelStore.delete(modelId)
        }
    }

    fun deletePartialDownload(modelId: String) {
        viewModelScope.launch {
            val model = localModelStore.getById(modelId)
            if (model != null) {
                val file = File(modelsDir, model.modelFileName)
                if (file.exists()) file.delete()
            }
            localModelStore.delete(modelId)
        }
    }

    fun updateGpuOffloadPercent(percent: Int) {
        viewModelScope.launch {
            localModelStore.setGpuOffloadPercent(percent)
        }
    }

    fun unloadModel() {
        llmEngine.unload()
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            try {
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_model.gguf"
                val destFile = File(modelsDir, fileName)

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("Cannot open file")

                if (!isValidGguf(destFile)) {
                    destFile.delete()
                    _uiState.update { it.copy(errorMessage = "Invalid GGUF file") }
                    return@launch
                }

                val model = LocalModel(
                    id = "imported-${System.currentTimeMillis()}",
                    name = fileName.removeSuffix(".gguf"),
                    parameterCount = "?",
                    quantization = "?",
                    modelFileName = fileName,
                    modelSizeBytes = destFile.length(),
                    downloadStatus = DownloadStatus.COMPLETE,
                    downloadedBytes = destFile.length(),
                    isImported = true,
                )
                localModelStore.save(model)
                localModelStore.setActiveModelId(model.id)
                Log.i(TAG, "Model imported: ${model.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                _uiState.update { it.copy(errorMessage = "Import failed: ${e.message}") }
            }
        }
    }

    fun getDeviceRamMb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
