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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.localImageModelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_image_models",
)

/** On-device diffusion models, kept apart from chat models so neither picker sees the other's files. */
class LocalImageModelStore(context: Context) {

    private val dataStore = context.localImageModelDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val MODELS = stringPreferencesKey("image_models_json")
    }

    val models: Flow<List<LocalImageModel>> = dataStore.data.map { prefs ->
        prefs[Keys.MODELS]?.let { raw ->
            runCatching { json.decodeFromString<List<LocalImageModel>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun save(model: LocalImageModel) {
        dataStore.edit { prefs ->
            val updated = getModels(prefs).filter { it.id != model.id } + model
            prefs[Keys.MODELS] = json.encodeToString(updated)
        }
    }

    suspend fun delete(modelId: String) {
        dataStore.edit { prefs ->
            prefs[Keys.MODELS] = json.encodeToString(getModels(prefs).filter { it.id != modelId })
        }
    }

    suspend fun getById(modelId: String): LocalImageModel? = models.first().firstOrNull { it.id == modelId }

    suspend fun updateStatus(
        modelId: String,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        errorMessage: String? = null,
    ) {
        dataStore.edit { prefs ->
            val updated = getModels(prefs).map {
                if (it.id == modelId) it.copy(downloadStatus = status, downloadedBytes = downloadedBytes, errorMessage = errorMessage)
                else it
            }
            prefs[Keys.MODELS] = json.encodeToString(updated)
        }
    }

    private fun getModels(prefs: Preferences): List<LocalImageModel> =
        prefs[Keys.MODELS]?.let { raw ->
            runCatching { json.decodeFromString<List<LocalImageModel>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
}
