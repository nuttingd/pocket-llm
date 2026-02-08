package dev.nutting.pocketllm.data.local.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.localModelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_models",
)

class LocalModelStore(context: Context) {

    private val dataStore = context.localModelDataStore

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val MODELS = stringPreferencesKey("models_json")
    }

    val models: Flow<List<LocalModel>> = dataStore.data.map { prefs ->
        val raw = prefs[Keys.MODELS] ?: return@map emptyList()
        try {
            json.decodeFromString<List<LocalModel>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun save(model: LocalModel) {
        dataStore.edit { prefs ->
            val current = readModels(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == model.id }
            if (index >= 0) {
                current[index] = model
            } else {
                current.add(model)
            }
            prefs[Keys.MODELS] = json.encodeToString(current)
        }
    }

    suspend fun delete(modelId: String) {
        dataStore.edit { prefs ->
            val current = readModels(prefs).filterNot { it.id == modelId }
            prefs[Keys.MODELS] = json.encodeToString(current)
        }
    }

    suspend fun getById(modelId: String): LocalModel? {
        var result: LocalModel? = null
        dataStore.edit { prefs ->
            result = readModels(prefs).find { it.id == modelId }
        }
        return result
    }

    suspend fun updateStatus(modelId: String, status: DownloadStatus, downloadedBytes: Long = 0L) {
        dataStore.edit { prefs ->
            val current = readModels(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == modelId }
            if (index >= 0) {
                current[index] = current[index].copy(
                    downloadStatus = status,
                    downloadedBytes = downloadedBytes,
                )
                prefs[Keys.MODELS] = json.encodeToString(current)
            }
        }
    }

    private fun readModels(prefs: Preferences): List<LocalModel> {
        val raw = prefs[Keys.MODELS] ?: return emptyList()
        return try {
            json.decodeFromString<List<LocalModel>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
