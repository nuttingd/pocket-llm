package dev.nutting.pocketllm.domain.image

import dev.nutting.pocketllm.data.local.entity.ImageDialect
import dev.nutting.pocketllm.data.remote.image.OpenAiImagesRequest
import dev.nutting.pocketllm.data.remote.image.SdCppGuidance
import dev.nutting.pocketllm.data.remote.image.SdCppImgGenRequest
import dev.nutting.pocketllm.data.remote.image.SdCppLimits
import dev.nutting.pocketllm.data.remote.image.SdCppSampleParams
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Per-conversation image settings. Null means "server default" (or the app default where one is required). */
@Serializable
data class ImageParameters(
    val width: Int? = null,
    val height: Int? = null,
    val count: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    /** Null means a fresh random seed per generation. */
    val seed: Long? = null,
)

/** The concrete values sent for one job, stored with the job and its images. */
@Serializable
data class ResolvedImageParameters(
    val width: Int,
    val height: Int,
    val count: Int,
    /** Null when the server doesn't accept a seed (generic OpenAI images API). */
    val seed: Long? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val negativePrompt: String? = null,
) {
    /** sd-server gives batch image `i` the seed `seed + i`. */
    fun seedOf(index: Int): Long? = seed?.plus(index)
}

/**
 * Resolves settings into what's actually sent:
 * - **Size:** always sent (sd-server recipes can default to 2048², and "auto" isn't understood). It's clamped
 *   to the server's limits and snapped to a multiple of 32, which Qwen-Image requires and all sd.cpp models accept.
 * - **Count:** clamped to 1..[MAX_COUNT] and the server's batch limit.
 * - **Native parameters:** for [ImageDialect.SD_CPP] a seed is always chosen (sd-server's default is a fixed 42).
 *   The generic dialect drops seed, steps, CFG, sampler and negative prompt, because there's no standard
 *   spelling for them and servers silently ignore unknown fields.
 */
fun ImageParameters.resolve(
    dialect: ImageDialect,
    limits: SdCppLimits? = null,
    random: Random = Random.Default,
    /** What to keep out of the image; given with each prompt, like the prompt itself. */
    negativePrompt: String? = null,
): ResolvedImageParameters {
    val minW = limits?.minWidth ?: MIN_SIDE
    val maxW = limits?.maxWidth ?: MAX_SIDE
    val minH = limits?.minHeight ?: MIN_SIDE
    val maxH = limits?.maxHeight ?: MAX_SIDE
    val maxCount = minOf(MAX_COUNT, limits?.maxBatchCount ?: MAX_COUNT).coerceAtLeast(1)
    val native = dialect == ImageDialect.SD_CPP
    return ResolvedImageParameters(
        width = snapSide(width ?: DEFAULT_SIDE, minW, maxW),
        height = snapSide(height ?: DEFAULT_SIDE, minH, maxH),
        count = (count ?: 1).coerceIn(1, maxCount),
        seed = if (native) (seed ?: random.nextLong(0, MAX_RANDOM_SEED)) else null,
        steps = steps.takeIf { native },
        cfg = cfg.takeIf { native },
        sampler = sampler?.takeIf { native && it.isNotBlank() },
        scheduler = scheduler?.takeIf { native && it.isNotBlank() },
        negativePrompt = negativePrompt?.takeIf { native && it.isNotBlank() },
    )
}

/** Rounds to the nearest multiple of 32 within [min, max] (bounds themselves snapped inward). */
internal fun snapSide(value: Int, min: Int, max: Int): Int {
    val lo = ((min + SIDE_STEP - 1) / SIDE_STEP * SIDE_STEP).coerceAtLeast(SIDE_STEP)
    val hi = (max / SIDE_STEP * SIDE_STEP).coerceAtLeast(lo)
    val rounded = (value + SIDE_STEP / 2) / SIDE_STEP * SIDE_STEP
    return rounded.coerceIn(lo, hi)
}

fun ResolvedImageParameters.toSdCppRequest(
    prompt: String,
    refImages: List<String> = emptyList(),
    previewMode: String? = null,
): SdCppImgGenRequest {
    val guidance = cfg?.let { SdCppGuidance(txtCfg = it) }
    val sample = SdCppSampleParams(scheduler = scheduler, sampleMethod = sampler, sampleSteps = steps, guidance = guidance)
        .takeIf { scheduler != null || sampler != null || steps != null || guidance != null }
    return SdCppImgGenRequest(
        prompt = prompt,
        negativePrompt = negativePrompt,
        width = width,
        height = height,
        seed = requireNotNull(seed) { "sd-server requests always carry a seed" },
        batchCount = count.takeIf { it > 1 },
        sampleParams = sample,
        refImages = refImages.takeIf { it.isNotEmpty() },
        preview = previewMode,
        previewInterval = previewMode?.let { 1 },
    )
}

fun ResolvedImageParameters.toOpenAiRequest(model: String, prompt: String): OpenAiImagesRequest =
    OpenAiImagesRequest(model = model, prompt = prompt, n = count, size = "${width}x$height")

const val DEFAULT_SIDE = 1024
const val MAX_COUNT = 4
private const val SIDE_STEP = 32
private const val MIN_SIDE = 64
private const val MAX_SIDE = 4096
/** Seeds stay within a signed 32-bit range, which every sd.cpp build accepts. */
private const val MAX_RANDOM_SEED = Int.MAX_VALUE.toLong()
