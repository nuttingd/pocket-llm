package dev.nutting.pocketllm.ui.server

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.entity.ServerKind
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.model.imageModels
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
import dev.nutting.pocketllm.domain.image.ImageServerDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ServerConfigViewModel(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    /** Tells stable-diffusion.cpp servers apart from others; null disables detection (e.g. in tests). */
    private val imageServerDetector: ImageServerDetector? = null,
) : ViewModel() {

    companion object {
        private const val TAG = "ServerConfigViewModel"
        /** Wait for typing to pause before probing the URL. */
        private const val DETECT_DEBOUNCE_MS = 700L
    }

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    private var detectJob: Job? = null

    init {
        viewModelScope.launch {
            serverRepository.getAll().collect { servers ->
                _uiState.update { it.copy(servers = servers) }
                describeImageServers(servers.filter { it.kind == ServerKind.IMAGE })
            }
        }
    }

    /** Fills in what each image server is, for the server list. */
    private fun describeImageServers(servers: List<ServerProfileEntity>) {
        val detector = imageServerDetector ?: return
        servers.filter { it.id !in _uiState.value.imageServerDescriptions }.forEach { server ->
            viewModelScope.launch {
                val description = detector.serverInfo(server).description
                _uiState.update { it.copy(imageServerDescriptions = it.imageServerDescriptions + (server.id to description)) }
            }
        }
    }

    fun startAddServer() {
        _uiState.update { it.copy(editingServer = EditingServer()) }
    }

    fun startEditServer(server: ServerProfileEntity) {
        val editing = EditingServer(
            id = server.id,
            name = server.name,
            baseUrl = server.baseUrl,
            hasStoredApiKey = server.hasApiKey,
            requestTimeoutSeconds = server.requestTimeoutSeconds,
            imageTimeoutMinutes = server.imageTimeoutSeconds / 60,
            kind = server.kind,
            // An existing server's type is the user's earlier choice; detection only informs
            kindChosenByUser = true,
            imageModelId = server.imageModelId.orEmpty(),
        )
        _uiState.update { it.copy(editingServer = editing) }
        detectSoon(editing, immediately = true)
    }

    fun updateEditingServer(editing: EditingServer) {
        val previous = _uiState.value.editingServer
        _uiState.update { it.copy(editingServer = editing) }
        if (previous == null || previous.baseUrl != editing.baseUrl || previous.apiKey != editing.apiKey) {
            detectSoon(editing)
        }
    }

    /** The user picks Chat or Image generation; detection won't change it afterwards. */
    fun setKind(kind: ServerKind) {
        val editing = _uiState.value.editingServer ?: return
        _uiState.update { it.copy(editingServer = editing.copy(kind = kind, kindChosenByUser = true)) }
        if (kind == ServerKind.IMAGE && editing.suggestedModels.isEmpty()) detectSoon(editing, immediately = true)
    }

    /** Probes the URL being edited once typing pauses: is it stable-diffusion.cpp, and what models does it list? */
    private fun detectSoon(editing: EditingServer, immediately: Boolean = false) {
        val detector = imageServerDetector ?: return
        detectJob?.cancel()
        val baseUrl = editing.baseUrl.trim().trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            _uiState.update { state -> state.copy(editingServer = state.editingServer?.copy(detection = Detection.Idle)) }
            return
        }
        detectJob = viewModelScope.launch {
            if (!immediately) delay(DETECT_DEBOUNCE_MS)
            _uiState.update { state -> state.copy(editingServer = state.editingServer?.copy(detection = Detection.Checking)) }
            val apiKey = editing.apiKey.ifBlank { null }
                ?: editing.id?.takeIf { editing.hasStoredApiKey }?.let { serverRepository.getApiKey(it).first() }
            val detection = try {
                Detection.Found(detector.probe(baseUrl, apiKey))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't reach $baseUrl to detect its type", e)
                Detection.Unreachable(e.message ?: "Couldn't reach the server")
            }
            // Models double as image model suggestions for OpenAI-compatible image servers
            val models = if (detection is Detection.Found) {
                runCatching { serverRepository.fetchModelsAt(baseUrl, apiKey).imageModels().map { it.id } }
                    .onFailure { Log.w(TAG, "Couldn't list models at $baseUrl", it) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _uiState.update { state ->
                state.copy(editingServer = state.editingServer?.withDetection(detection)?.copy(suggestedModels = models))
            }
        }
    }

    fun cancelEdit() {
        detectJob?.cancel()
        _uiState.update { it.copy(editingServer = null, error = null) }
    }

    fun saveServer() {
        val editing = _uiState.value.editingServer ?: return
        if (editing.name.isBlank() || editing.baseUrl.isBlank()) {
            _uiState.update { it.copy(error = "Name and URL are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val now = System.currentTimeMillis()
                val id = editing.id ?: UUID.randomUUID().toString()
                val isImage = editing.kind == ServerKind.IMAGE
                val profile = ServerProfileEntity(
                    id = id,
                    name = editing.name,
                    baseUrl = editing.baseUrl.trim().trimEnd('/'),
                    hasApiKey = editing.apiKey.isNotBlank() || editing.hasStoredApiKey,
                    requestTimeoutSeconds = editing.requestTimeoutSeconds,
                    imageTimeoutSeconds = editing.imageTimeoutMinutes.coerceIn(1, 24 * 60) * 60,
                    kind = editing.kind,
                    imageModelId = editing.imageModelId.trim().takeIf { isImage && editing.needsImageModel && it.isNotEmpty() },
                    createdAt = now,
                    updatedAt = now,
                )

                if (editing.id != null) {
                    serverRepository.update(profile)
                } else {
                    serverRepository.insert(profile)
                }

                if (editing.apiKey.isNotBlank()) {
                    serverRepository.saveApiKey(id, editing.apiKey)
                }

                // The chat picker remembers the last chat server; an image server is chosen in image mode
                if (!isImage) settingsRepository.setLastActiveServerId(id)
                // The URL may have changed, so what the server is may have too
                imageServerDetector?.invalidate(id)
                _uiState.update { it.copy(editingServer = null, isLoading = false, imageServerDescriptions = it.imageServerDescriptions - id) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save server", e)
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            serverRepository.delete(serverId)
        }
    }

    fun validateAndFetchModels(serverId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, error = null, testResult = null, models = emptyList()) }
            val server = serverRepository.getById(serverId).first() ?: return@launch
            if (server.kind == ServerKind.IMAGE) {
                testImageServer(server)
                return@launch
            }
            serverRepository.validateConnection(serverId).fold(
                onSuccess = { models ->
                    _uiState.update {
                        it.copy(models = models, isTesting = false, testResult = "Connected — ${models.size} model(s) available")
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Connection test failed", e)
                    _uiState.update { it.copy(error = e.message, isTesting = false) }
                },
            )
        }
    }

    private suspend fun testImageServer(server: ServerProfileEntity) {
        val detector = imageServerDetector
        try {
            val apiKey = if (server.hasApiKey) serverRepository.getApiKey(server.id).first() else null
            val info = detector?.probe(server.baseUrl, apiKey)
            val result = info?.description ?: "Connected"
            _uiState.update {
                it.copy(
                    isTesting = false,
                    testResult = "Connected — $result",
                    imageServerDescriptions = it.imageServerDescriptions + (server.id to result),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Image server test failed", e)
            _uiState.update { it.copy(error = e.message ?: "Couldn't reach the server", isTesting = false) }
        }
    }

    fun selectModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId) }
        viewModelScope.launch {
            settingsRepository.setLastActiveModelId(modelId)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
