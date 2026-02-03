package dev.nutting.pocketllm.data.repository

import dev.nutting.pocketllm.data.local.dao.ServerProfileDao
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.preferences.EncryptedDataStore
import dev.nutting.pocketllm.data.remote.OpenAiApiClient
import dev.nutting.pocketllm.data.remote.model.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ServerRepository(
    private val dao: ServerProfileDao,
    private val encryptedDataStore: EncryptedDataStore,
    private val apiClient: OpenAiApiClient,
) {
    fun getAll(): Flow<List<ServerProfileEntity>> = dao.getAll()

    fun getById(id: String): Flow<ServerProfileEntity?> = dao.getById(id)

    suspend fun insert(profile: ServerProfileEntity) = dao.insert(profile)

    suspend fun update(profile: ServerProfileEntity) = dao.update(profile)

    suspend fun delete(id: String) {
        dao.deleteById(id)
        encryptedDataStore.deleteApiKey(id)
    }

    suspend fun saveApiKey(serverId: String, apiKey: String) {
        encryptedDataStore.saveApiKey(serverId, apiKey)
        val profile = dao.getById(serverId).first() ?: return
        dao.update(profile.copy(hasApiKey = true, updatedAt = System.currentTimeMillis()))
    }

    fun getApiKey(serverId: String): Flow<String?> = encryptedDataStore.getApiKey(serverId)

    suspend fun fetchModels(serverId: String): List<ModelInfo> {
        val profile = dao.getById(serverId).first()
            ?: throw IllegalArgumentException("Server profile not found: $serverId")
        val apiKey = if (profile.hasApiKey) encryptedDataStore.getApiKey(serverId).first() else null
        return apiClient.fetchModels(
            baseUrl = profile.baseUrl,
            apiKey = apiKey,
            timeoutSeconds = profile.requestTimeoutSeconds.toLong(),
        )
    }

    suspend fun validateConnection(serverId: String): Result<List<ModelInfo>> = runCatching {
        fetchModels(serverId)
    }
}
