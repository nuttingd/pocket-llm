package dev.nutting.pocketllm.data.remote.huggingface

import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import java.net.URLEncoder
import kotlin.math.ceil

/** A repository returned by the Hugging Face model search API. */
@Serializable
data class HfModelSummary(
    val id: String,
    val downloads: Long = 0L,
    val likes: Long = 0L,
    // "gated" is either `false` or a string like "auto" / "manual"
    val gated: JsonElement? = null,
) {
    val isGated: Boolean
        get() = when (val g = gated) {
            null -> false
            is JsonPrimitive -> if (g.isString) g.content != "false" else g.booleanOrNull == true
            else -> true
        }
}

/** A raw entry from the `/api/models/{repo}/tree/{revision}` endpoint. */
@Serializable
data class HfTreeEntry(
    val type: String,
    val path: String,
    val size: Long = 0L,
    val lfs: HfLfsInfo? = null,
)

@Serializable
data class HfLfsInfo(val size: Long = 0L)

/** A single downloadable GGUF file in a repository. */
data class HfGgufFile(
    val repoId: String,
    val path: String,
    val sizeBytes: Long,
    val revision: String = HuggingFace.DEFAULT_REVISION,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val quantization: String get() = HuggingFace.quantizationFromFileName(fileName)
    val downloadUrl: String get() = HuggingFace.resolveUrl(repoId, path, revision)
}

/** The GGUF contents of a repository, split into model weights and vision projectors. */
data class HfRepoListing(
    val repoId: String,
    val modelFiles: List<HfGgufFile>,
    val projectorFiles: List<HfGgufFile>,
    /** Number of multi-part (split) GGUF shards that were skipped because they aren't supported. */
    val skippedSplitFiles: Int,
)

/**
 * A user-supplied pointer to a Hugging Face model: a repo ID, a repo URL, or a direct file URL.
 */
data class HfReference(
    val repoId: String,
    val filePath: String? = null,
    val revision: String = HuggingFace.DEFAULT_REVISION,
)

object HuggingFace {

    const val BASE_URL = "https://huggingface.co"
    const val DEFAULT_REVISION = "main"
    const val MODEL_ID_PREFIX = "hf:"

    private val HF_HOSTS = setOf("huggingface.co", "www.huggingface.co", "hf.co")

    private val REPO_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val SPLIT_SHARD = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    // Ordered most-specific first so e.g. "IQ4_XS" wins over "Q4", "Q4_K_M" over "Q4_K"
    private val QUANT_PATTERN = Regex(
        "(?<![A-Za-z0-9])(" +
            "I?Q[1-8]_[0-9A-Z]+(?:_[A-Z0-9]+)*" +
            "|TQ[12]_0" +
            "|MXFP4(?:_MOE)?" +
            "|BF16|F16|F32|FP16|FP32" +
            ")(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    private val PARAM_PATTERN = Regex(
        "(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)\\s*([BM])(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val MOE_PARAM_PATTERN = Regex(
        "(?<![A-Za-z0-9.])(\\d+)x(\\d+(?:\\.\\d+)?)([BM])(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses a repo ID ("owner/name"), repo URL, or file URL
     * (".../resolve/main/file.gguf" or ".../blob/main/file.gguf"). Returns null if the input
     * doesn't look like a Hugging Face reference (e.g. it's a free-text search query).
     */
    fun parseReference(input: String): HfReference? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null

        val hasScheme = text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        val hostPrefixed = HF_HOSTS.any { text.startsWith("$it/", ignoreCase = true) }
        if (hasScheme || hostPrefixed) {
            val uri = try {
                URI(if (hasScheme) text else "https://$text")
            } catch (_: Exception) {
                return null
            }
            if (uri.host?.lowercase() !in HF_HOSTS) return null
            text = uri.path.orEmpty().trim('/')
        }

        // "owner/repo:Q4_K_M" style tags (as used by llama.cpp / Ollama) — drop the tag
        val segments = text.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val owner = segments[0]
        val name = segments[1].substringBefore(':')
        if (!REPO_SEGMENT.matches(owner) || !REPO_SEGMENT.matches(name)) return null
        // Reserved top-level paths aren't model repos
        if (owner in setOf("datasets", "spaces", "api", "docs", "models")) return null

        val repoId = "$owner/$name"
        if (segments.size >= 4 && (segments[2] == "resolve" || segments[2] == "blob")) {
            val revision = segments[3]
            val filePath = segments.drop(4).joinToString("/").ifEmpty { null }
            return HfReference(repoId, filePath, revision)
        }
        if (segments.size >= 4 && segments[2] == "tree") {
            return HfReference(repoId, revision = segments[3])
        }
        return HfReference(repoId)
    }

    fun isHuggingFaceUrl(url: String): Boolean = try {
        URI(url).host?.lowercase() in HF_HOSTS
    } catch (_: Exception) {
        false
    }

    fun resolveUrl(repoId: String, path: String, revision: String = DEFAULT_REVISION): String {
        val encodedPath = path.split('/').joinToString("/") { encodeSegment(it) }
        return "$BASE_URL/$repoId/resolve/${encodeSegment(revision)}/$encodedPath"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    fun isSplitShard(fileName: String): Boolean = SPLIT_SHARD.containsMatchIn(fileName)

    fun isProjector(fileName: String): Boolean = fileName.contains("mmproj", ignoreCase = true)

    fun quantizationFromFileName(fileName: String): String {
        val base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")
        return QUANT_PATTERN.findAll(base).lastOrNull()?.value?.uppercase() ?: "?"
    }

    fun parameterCountFromName(name: String): String {
        MOE_PARAM_PATTERN.find(name)?.let { m ->
            return "${m.groupValues[1]}x${m.groupValues[2]}${m.groupValues[3].uppercase()}"
        }
        // The lookbehind skips version numbers ("Qwen3", "v0.3") and MoE active counts ("A3B")
        val m = PARAM_PATTERN.find(name) ?: return "?"
        return "${m.groupValues[1]}${m.groupValues[2].uppercase()}"
    }

    /** A rough lower bound on device RAM needed to run a model of the given total file size. */
    fun estimateMinimumRamMb(totalBytes: Long): Int {
        val mb = totalBytes / (1024.0 * 1024.0)
        // weights + ~25% for KV cache/activations + runtime overhead, rounded up to the next GB
        val estimate = mb * 1.25 + 512
        return (ceil(estimate / 1024.0).toInt() * 1024).coerceAtLeast(2048)
    }

    /** Collision-safe on-disk name for a file from a given repo. */
    fun localFileName(repoId: String, path: String): String {
        val raw = "${repoId.replace("/", "--")}--${path.substringAfterLast('/')}"
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    fun modelId(repoId: String, path: String): String = "$MODEL_ID_PREFIX$repoId/$path"

    fun displayName(repoId: String): String =
        repoId.substringAfter('/')
            .replace(Regex("[-_.]?GGUF$", RegexOption.IGNORE_CASE), "")
            .ifBlank { repoId }

    /** Partitions raw tree entries into supported model files and projector files. */
    fun buildListing(
        repoId: String,
        entries: List<HfTreeEntry>,
        revision: String = DEFAULT_REVISION,
    ): HfRepoListing {
        val ggufs = entries.filter { it.type == "file" && it.path.endsWith(".gguf", ignoreCase = true) }
        val (split, single) = ggufs.partition { isSplitShard(it.path.substringAfterLast('/')) }
        val files = single.map { HfGgufFile(repoId, it.path, it.lfs?.size?.takeIf { s -> s > 0 } ?: it.size, revision) }
        val (projectors, models) = files.partition { isProjector(it.fileName) }
        return HfRepoListing(
            repoId = repoId,
            modelFiles = models.sortedBy { it.sizeBytes },
            projectorFiles = projectors.sortedBy { it.sizeBytes },
            skippedSplitFiles = split.size,
        )
    }

    /** Picks a sensible default projector: Q8_0 if present, then F16, then the smallest. */
    fun defaultProjector(projectors: List<HfGgufFile>): HfGgufFile? =
        projectors.firstOrNull { it.quantization == "Q8_0" }
            ?: projectors.firstOrNull { it.quantization == "F16" }
            ?: projectors.minByOrNull { it.sizeBytes }

    fun toRegistryEntry(model: HfGgufFile, projector: HfGgufFile?): ModelRegistryEntry {
        val total = model.sizeBytes + (projector?.sizeBytes ?: 0L)
        val displayName = displayName(model.repoId)
        return ModelRegistryEntry(
            id = modelId(model.repoId, model.path),
            name = if (projector != null) "$displayName (Vision)" else displayName,
            description = model.repoId,
            parameterCount = parameterCountFromName(model.repoId).takeIf { it != "?" }
                ?: parameterCountFromName(model.fileName),
            quantization = model.quantization,
            modelDownloadUrl = model.downloadUrl,
            modelFileName = localFileName(model.repoId, model.path),
            modelSizeBytes = model.sizeBytes,
            minimumRamMb = estimateMinimumRamMb(total),
            projectorDownloadUrl = projector?.downloadUrl,
            projectorFileName = projector?.let { localFileName(it.repoId, it.path) },
            projectorSizeBytes = projector?.sizeBytes ?: 0L,
        )
    }
}
