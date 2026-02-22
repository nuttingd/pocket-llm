package dev.nutting.pocketllm.data.local.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val Context.localModelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_models",
)

class LocalModelStore(context: Context) {

    private val dataStore = context.localModelDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val MODELS = stringPreferencesKey("models_json")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val GPU_OFFLOAD_PERCENT = stringPreferencesKey("gpu_offload_percent")
    }

    val models: Flow<List<LocalModel>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.MODELS] ?: return@map emptyList()
        try {
            json.decodeFromString<List<LocalModel>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    val activeModelId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_MODEL_ID]
    }

    val gpuOffloadPercent: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.GPU_OFFLOAD_PERCENT]?.toIntOrNull() ?: 80
    }

    suspend fun save(model: LocalModel) {
        dataStore.edit { prefs ->
            val current = getModels(prefs)
            val updated = current.filter { it.id != model.id } + model
            prefs[Keys.MODELS] = json.encodeToString(updated)
        }
    }

    suspend fun delete(modelId: String) {
        dataStore.edit { prefs ->
            val current = getModels(prefs)
            prefs[Keys.MODELS] = json.encodeToString(current.filter { it.id != modelId })
            if (prefs[Keys.ACTIVE_MODEL_ID] == modelId) {
                prefs.remove(Keys.ACTIVE_MODEL_ID)
            }
        }
    }

    suspend fun getById(modelId: String): LocalModel? {
        return models.first().find { it.id == modelId }
    }

    suspend fun updateStatus(modelId: String, status: DownloadStatus, downloadedBytes: Long = 0L) {
        dataStore.edit { prefs ->
            val current = getModels(prefs)
            val updated = current.map {
                if (it.id == modelId) it.copy(downloadStatus = status, downloadedBytes = downloadedBytes)
                else it
            }
            prefs[Keys.MODELS] = json.encodeToString(updated)
        }
    }

    suspend fun setActiveModelId(modelId: String?) {
        dataStore.edit { prefs ->
            if (modelId != null) {
                prefs[Keys.ACTIVE_MODEL_ID] = modelId
            } else {
                prefs.remove(Keys.ACTIVE_MODEL_ID)
            }
        }
    }

    suspend fun setGpuOffloadPercent(percent: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.GPU_OFFLOAD_PERCENT] = percent.toString()
        }
    }

    private fun getModels(prefs: Preferences): List<LocalModel> {
        val raw = prefs[Keys.MODELS] ?: return emptyList()
        return try {
            json.decodeFromString<List<LocalModel>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
