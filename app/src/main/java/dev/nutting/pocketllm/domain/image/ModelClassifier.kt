package dev.nutting.pocketllm.domain.image

import android.util.Log
import dev.nutting.pocketllm.data.local.dao.ModelClassificationDao
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ModelClassificationEntity
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppCapabilities
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** How the app talks to a server's image models, and what the server supports (sd-server only). */
data class ImageServerInfo(val dialect: ImageDialect, val capabilities: SdCppCapabilities? = null)

/**
 * Decides whether a (server, model) generates images: a user override wins; otherwise every model on an
 * sd-server (detected by probing its native API) is an image model, and everything else is a chat model.
 * Probe results are cached per server; a failed probe isn't cached, so the next check tries again.
 */
class ModelClassifier(
    private val sdCpp: SdCppImageClient,
    private val dao: ModelClassificationDao,
    private val apiKey: suspend (serverId: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val info: ImageServerInfo, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun serverInfo(server: ServerProfileEntity, forceRefresh: Boolean = false): ImageServerInfo {
        val cached = cache[server.id]
        if (!forceRefresh && cached != null && clock() - cached.at < CACHE_TTL_MS) return cached.info
        return try {
            val capabilities = sdCpp.probe(server.baseUrl, apiKey(server.id))
            val info = if (capabilities != null) ImageServerInfo(ImageDialect.SD_CPP, capabilities) else ImageServerInfo(ImageDialect.OPENAI_IMAGES)
            cache[server.id] = Cached(info, clock())
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't probe ${server.name} for image support; using the last known result", e)
            cached?.info ?: ImageServerInfo(ImageDialect.OPENAI_IMAGES)
        }
    }

    /** The server's image info only if it's already known, without network access. */
    fun cachedServerInfo(serverId: String): ImageServerInfo? = cache[serverId]?.info

    suspend fun isImageModel(server: ServerProfileEntity, modelId: String): Boolean {
        val override = dao.observeForServer(server.id).first().firstOrNull { it.modelId == modelId }
        return override?.isImageModel ?: (serverInfo(server).dialect == ImageDialect.SD_CPP)
    }

    /** User overrides for a server's models: modelId → generates images. */
    fun overrides(serverId: String): Flow<Map<String, Boolean>> =
        dao.observeForServer(serverId).map { rows -> rows.associate { it.modelId to it.isImageModel } }

    /** Sets (or with null, clears) the user's override for a model. */
    suspend fun setOverride(serverId: String, modelId: String, isImageModel: Boolean?) {
        if (isImageModel == null) dao.delete(serverId, modelId)
        else dao.upsert(ModelClassificationEntity(serverId, modelId, isImageModel))
    }

    fun invalidate(serverId: String) {
        cache.remove(serverId)
    }

    companion object {
        private const val TAG = "ModelClassifier"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
