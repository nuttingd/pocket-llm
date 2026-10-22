package dev.nutting.pocketllm.domain.image

import android.util.Log
import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.local.entity.ServerProfileEntity
import dev.nutting.pocketllm.data.remote.image.SdCppCapabilities
import dev.nutting.pocketllm.data.remote.image.SdCppImageClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** How the app talks to an image server, and what the server supports (stable-diffusion.cpp only). */
data class ImageServerInfo(val dialect: ImageDialect, val capabilities: SdCppCapabilities? = null) {
    /** "stable-diffusion.cpp · <model>" or "OpenAI images API", for showing what the server is. */
    val description: String
        get() = when (dialect) {
            ImageDialect.SD_CPP -> listOfNotNull("stable-diffusion.cpp", capabilities?.model?.name).joinToString(" · ")
            ImageDialect.OPENAI_IMAGES -> "OpenAI images API"
        }
}

/**
 * Finds out how to talk to an image server by probing for stable-diffusion.cpp's native API; any other image
 * server is spoken to through the OpenAI images API. Results are cached per server; a failed probe isn't cached,
 * so the next check tries again.
 */
class ImageServerDetector(
    private val sdCpp: SdCppImageClient,
    private val apiKey: suspend (serverId: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val info: ImageServerInfo, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun serverInfo(server: ServerProfileEntity, forceRefresh: Boolean = false): ImageServerInfo {
        val cached = cache[server.id]
        if (!forceRefresh && cached != null && clock() - cached.at < CACHE_TTL_MS) return cached.info
        return try {
            val info = probe(server.baseUrl, apiKey(server.id))
            cache[server.id] = Cached(info, clock())
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't probe ${server.name} for image support; using the last known result", e)
            cached?.info ?: ImageServerInfo(ImageDialect.OPENAI_IMAGES)
        }
    }

    /** Probes a server that may not be saved yet (e.g. while it's being added). Throws when it can't be reached. */
    suspend fun probe(baseUrl: String, apiKey: String?): ImageServerInfo {
        val capabilities = sdCpp.probe(baseUrl, apiKey)
        return if (capabilities != null) ImageServerInfo(ImageDialect.SD_CPP, capabilities) else ImageServerInfo(ImageDialect.OPENAI_IMAGES)
    }

    /** The server's image info only if it's already known, without network access. */
    fun cachedServerInfo(serverId: String): ImageServerInfo? = cache[serverId]?.info

    fun invalidate(serverId: String) {
        cache.remove(serverId)
    }

    companion object {
        private const val TAG = "ImageServerDetector"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
