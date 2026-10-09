package dev.nutting.pocketllm.ui.modelmanagement

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.local.model.ModelRegistry
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import dev.nutting.pocketllm.domain.LocalLlmClient
import dev.nutting.pocketllm.domain.LocalModelState
import dev.nutting.pocketllm.llm.LlmEngine
import dev.nutting.pocketllm.util.ModelDownloadManager
import dev.nutting.pocketllm.util.deviceTotalRamMb
import kotlinx.coroutines.CancellationException
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
    val localModelState: LocalModelState = LocalModelState.NotLoaded,
    val deviceInfo: String = "",
    val errorMessage: String? = null,
    val showCellularWarning: Boolean = false,
    val pendingDownloadEntry: ModelRegistryEntry? = null,
)

class ModelManagementViewModel(
    private val localModelStore: LocalModelStore,
    private val downloadManager: ModelDownloadManager,
    private val llmEngine: LlmEngine,
    private val localLlmClient: LocalLlmClient,
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
            localLlmClient.modelState.collect { modelState ->
                _uiState.update { it.copy(localModelState = modelState) }
            }
        }
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
        when (val check = downloadManager.precheck(entry.totalSizeBytes)) {
            is ModelDownloadManager.Precheck.InsufficientStorage ->
                _uiState.update { it.copy(errorMessage = check.message) }
            ModelDownloadManager.Precheck.CellularNetwork ->
                _uiState.update { it.copy(showCellularWarning = true, pendingDownloadEntry = entry) }
            ModelDownloadManager.Precheck.Ok -> startDownload(entry)
        }
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
        viewModelScope.launch { downloadManager.start(entry) }
    }

    fun cancelDownload(modelId: String) {
        viewModelScope.launch { downloadManager.cancel(modelId) }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            localModelStore.setActiveModelId(modelId)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (localLlmClient.modelState.value.let { it is LocalModelState.Loaded && it.modelId == modelId }) {
                localLlmClient.releaseMemory(cancelInFlight = true)
            }
            val model = localModelStore.getById(modelId)
            if (model != null) {
                File(modelsDir, model.modelFileName).let { if (it.exists()) it.delete() }
                if (model.projectorFileName.isNotEmpty()) {
                    File(modelsDir, model.projectorFileName).let { if (it.exists()) it.delete() }
                }
            }
            localModelStore.delete(modelId)
        }
    }

    fun retryDownload(modelId: String) {
        viewModelScope.launch {
            // Imported models have no source URL — delete and let the user re-import
            if (!downloadManager.retry(modelId)) deletePartialDownload(modelId)
        }
    }

    fun deletePartialDownload(modelId: String) {
        viewModelScope.launch {
            val model = localModelStore.getById(modelId)
            if (model != null) {
                File(modelsDir, model.modelFileName).let { if (it.exists()) it.delete() }
                if (model.projectorFileName.isNotEmpty()) {
                    File(modelsDir, model.projectorFileName).let { if (it.exists()) it.delete() }
                }
            }
            localModelStore.delete(modelId)
        }
    }

    fun updateGpuOffloadPercent(percent: Int) {
        viewModelScope.launch {
            localModelStore.setGpuOffloadPercent(percent)
        }
    }

    /** Frees the loaded model in the background, stopping a reply in progress. */
    fun unloadModel() {
        localLlmClient.releaseMemory(cancelInFlight = true)
    }

    /** Makes [modelId] the active model and loads it now, reporting why if it can't be loaded. */
    fun loadModel(modelId: String) {
        viewModelScope.launch {
            localModelStore.setActiveModelId(modelId)
            try {
                localLlmClient.ensureModelLoaded(modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Model failed to load") }
            }
        }
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

    fun getDeviceRamMb(): Int = deviceTotalRamMb(appContext)

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
