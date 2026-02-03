package dev.nutting.pocketllm.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.repository.ServerRepository
import dev.nutting.pocketllm.data.repository.SettingsRepository
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState: StateFlow<ServerConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val lastServerId = settingsRepository.getLastActiveServerId().first()
            _uiState.update { it.copy(isFirstLaunch = lastServerId.isBlank()) }
        }
        viewModelScope.launch {
            serverRepository.getAll().collect { servers ->
                _uiState.update { it.copy(servers = servers) }
            }
        }
    }

    fun startAddServer() {
        _uiState.update { it.copy(editingServer = EditingServer()) }
    }

    fun startEditServer(server: ServerProfileEntity) {
        _uiState.update {
            it.copy(
                editingServer = EditingServer(
                    id = server.id,
                    name = server.name,
                    baseUrl = server.baseUrl,
                    requestTimeoutSeconds = server.requestTimeoutSeconds,
                ),
            )
        }
    }

    fun updateEditingServer(editing: EditingServer) {
        _uiState.update { it.copy(editingServer = editing) }
    }

    fun cancelEdit() {
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
                val profile = ServerProfileEntity(
                    id = id,
                    name = editing.name,
                    baseUrl = editing.baseUrl.trimEnd('/'),
                    hasApiKey = editing.apiKey.isNotBlank(),
                    requestTimeoutSeconds = editing.requestTimeoutSeconds,
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

                settingsRepository.setLastActiveServerId(id)
                _uiState.update { it.copy(editingServer = null, isLoading = false, isFirstLaunch = false) }
            } catch (e: Exception) {
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
            _uiState.update { it.copy(isTesting = true, error = null, testResult = null) }
            val result = serverRepository.validateConnection(serverId)
            result.fold(
                onSuccess = { models ->
                    _uiState.update {
                        it.copy(
                            models = models,
                            isTesting = false,
                            testResult = "Connected — ${models.size} model(s) available",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message, isTesting = false) }
                },
            )
        }
    }

    fun selectModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
