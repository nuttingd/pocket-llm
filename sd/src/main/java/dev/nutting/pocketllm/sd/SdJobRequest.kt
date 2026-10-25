package dev.nutting.pocketllm.sd

import kotlinx.serialization.Serializable

/** Everything the :sd process needs to run one job. Paths are absolute. */
@Serializable
data class SdJobRequest(
    val modelPath: String,
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val seed: Long,
    val count: Int,
    val outDir: String,
    val sampler: String? = null,
    val initImagePath: String? = null,
    val strength: Float? = null,
)

@Serializable
data class SdJobResult(val imagePaths: List<String>, val error: String? = null)

/** What the app process asks the :sd process to load. */
@Serializable
data class SdModelManifest(val modelPath: String)
