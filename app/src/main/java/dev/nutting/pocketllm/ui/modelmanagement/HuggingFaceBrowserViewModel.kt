package dev.nutting.pocketllm.ui.modelmanagement

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.remote.huggingface.HfGgufFile
import dev.nutting.pocketllm.data.remote.huggingface.HfModelSummary
import dev.nutting.pocketllm.data.remote.huggingface.HfReference
import dev.nutting.pocketllm.data.remote.huggingface.HfRepoListing
import dev.nutting.pocketllm.data.remote.huggingface.HuggingFace
import dev.nutting.pocketllm.data.remote.huggingface.HuggingFaceClient
import dev.nutting.pocketllm.util.ModelDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HuggingFaceBrowserUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val isLoadingRepo: Boolean = false,
    val repo: HfRepoListing? = null,
    val repoIsGated: Boolean = false,
    /** Path of the projector to bundle with downloads, or null for none. */
    val selectedProjectorPath: String? = null,
    /** Path of a file the user linked to directly, highlighted in the listing. */
    val highlightedPath: String? = null,
    val modelStatuses: Map<String, DownloadStatus> = emptyMap(),
    val hasToken: Boolean = false,
    val message: String? = null,
    val showCellularWarning: Boolean = false,
    val pendingDownloadEntry: ModelRegistryEntry? = null,
)

class HuggingFaceBrowserViewModel(
    private val client: HuggingFaceClient,
    private val encryptedDataStore: EncryptedDataStore,
    private val localModelStore: LocalModelStore,
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    companion object {
        private const val TAG = "HuggingFaceBrowserVM"
    }

    private val _uiState = MutableStateFlow(HuggingFaceBrowserUiState())
    val uiState: StateFlow<HuggingFaceBrowserUiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            localModelStore.models.collect { models ->
                _uiState.update { s -> s.copy(modelStatuses = models.associate { it.id to it.downloadStatus }) }
            }
        }
        viewModelScope.launch {
            encryptedDataStore.getHuggingFaceToken().collect { token ->
                _uiState.update { it.copy(hasToken = !token.isNullOrBlank()) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    /** Opens the repo directly if the query is a repo ID or URL, otherwise runs a search. */
    fun submitQuery() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        val ref = HuggingFace.parseReference(query)
        if (ref != null) openRepo(ref) else search(query)
    }

    private fun search(query: String) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, repo = null) }
            try {
                val results = client.searchModels(query, token())
                _uiState.update { it.copy(results = results, hasSearched = true, isSearching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                _uiState.update { it.copy(isSearching = false, message = errorText(e)) }
            }
        }
    }

    fun openRepo(summary: HfModelSummary) {
        openRepo(HfReference(summary.id), gated = summary.isGated)
    }

    private fun openRepo(ref: HfReference, gated: Boolean = false) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRepo = true) }
            try {
                val listing = client.listGgufFiles(ref.repoId, ref.revision, token())
                if (listing.modelFiles.isEmpty()) {
                    val reason = if (listing.skippedSplitFiles > 0) {
                        "${ref.repoId} only has multi-part GGUF files, which aren't supported yet."
                    } else {
                        "${ref.repoId} has no GGUF files. Look for a repo with \"GGUF\" in its name."
                    }
                    _uiState.update { it.copy(isLoadingRepo = false, message = reason) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoadingRepo = false,
                        repo = listing,
                        repoIsGated = gated,
                        selectedProjectorPath = HuggingFace.defaultProjector(listing.projectorFiles)?.path,
                        highlightedPath = ref.filePath?.takeIf { p -> listing.modelFiles.any { f -> f.path == p } },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list ${ref.repoId}", e)
                _uiState.update { it.copy(isLoadingRepo = false, message = errorText(e)) }
            }
        }
    }

    /** Returns to the search results. Returns false if there was no open repo. */
    fun closeRepo(): Boolean {
        if (_uiState.value.repo == null && !_uiState.value.isLoadingRepo) return false
        requestJob?.cancel()
        _uiState.update { it.copy(repo = null, isLoadingRepo = false, highlightedPath = null) }
        return true
    }

    fun selectProjector(path: String?) {
        _uiState.update { it.copy(selectedProjectorPath = path) }
    }

    fun buildEntry(file: HfGgufFile): ModelRegistryEntry {
        val state = _uiState.value
        val projector = state.repo?.projectorFiles?.firstOrNull { it.path == state.selectedProjectorPath }
        return HuggingFace.toRegistryEntry(file, projector)
    }

    fun download(file: HfGgufFile) {
        val entry = buildEntry(file)
        when (val check = downloadManager.precheck(entry.totalSizeBytes)) {
            is ModelDownloadManager.Precheck.InsufficientStorage ->
                _uiState.update { it.copy(message = check.message) }
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
        viewModelScope.launch {
            downloadManager.start(entry)
            _uiState.update { it.copy(message = "Downloading ${entry.name} (${entry.quantization})") }
        }
    }

    fun saveToken(token: String) {
        viewModelScope.launch {
            if (token.isBlank()) encryptedDataStore.deleteHuggingFaceToken()
            else encryptedDataStore.saveHuggingFaceToken(token.trim())
        }
    }

    fun clearToken() {
        viewModelScope.launch { encryptedDataStore.deleteHuggingFaceToken() }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun token(): String? = try {
        encryptedDataStore.getHuggingFaceToken().first()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read Hugging Face token", e)
        null
    }

    private fun errorText(e: Exception): String = e.message ?: "Request to Hugging Face failed"
}
