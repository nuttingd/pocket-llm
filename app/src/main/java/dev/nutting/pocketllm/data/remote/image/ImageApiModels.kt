package dev.nutting.pocketllm.data.remote.image

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Wire types for image servers. See specs/003-remote-image-generation/contracts/image-servers.md.
// Everything the app reads is nullable or defaulted so a server that omits a field doesn't fail parsing.

// ---- stable-diffusion.cpp sd-server native API (/sdcpp/v1) ----

@Serializable
data class SdCppCapabilities(
    val model: SdCppModel? = null,
    @SerialName("supported_modes") val supportedModes: List<String> = emptyList(),
    @SerialName("defaults_by_mode") val defaultsByMode: Map<String, SdCppDefaults> = emptyMap(),
    @SerialName("output_formats_by_mode") val outputFormatsByMode: Map<String, List<String>> = emptyMap(),
    @SerialName("features_by_mode") val featuresByMode: Map<String, Map<String, JsonElement>> = emptyMap(),
    val samplers: List<String> = emptyList(),
    val schedulers: List<String> = emptyList(),
    @SerialName("preview_modes") val previewModes: List<String> = emptyList(),
    val limits: SdCppLimits? = null,
) {
    val imgGenDefaults: SdCppDefaults? get() = defaultsByMode[IMG_GEN]

    fun supportsImgGen(): Boolean = IMG_GEN in supportedModes

    /**
     * The step count the server always uses, when it was started with a fixed noise schedule (`--sigmas`).
     * stable-diffusion.cpp then runs one step per sigma interval whatever a request asks for.
     */
    val fixedSteps: Int?
        get() = imgGenDefaults?.sampleParams?.customSigmas?.takeIf { it.size > 1 }?.let { it.size - 1 }

    /** A boolean feature flag for image generation, e.g. `ref_images`, `cancel_queued`, `preview`. */
    fun imgGenFeature(name: String): Boolean = imgGenFeatureOrNull(name) == true

    /** Like [imgGenFeature], but null when the server doesn't report the flag at all (e.g. an older build). */
    fun imgGenFeatureOrNull(name: String): Boolean? =
        (featuresByMode[IMG_GEN]?.get(name) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val IMG_GEN = "img_gen"
    }
}

@Serializable
data class SdCppModel(val name: String? = null, val stem: String? = null)

@Serializable
data class SdCppDefaults(
    val width: Int? = null,
    val height: Int? = null,
    val seed: Long? = null,
    @SerialName("batch_count") val batchCount: Int? = null,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    @SerialName("sample_params") val sampleParams: SdCppSampleParams? = null,
)

@Serializable
data class SdCppSampleParams(
    val scheduler: String? = null,
    @SerialName("sample_method") val sampleMethod: String? = null,
    @SerialName("sample_steps") val sampleSteps: Int? = null,
    val guidance: SdCppGuidance? = null,
    /** A fixed noise schedule (e.g. `--sigmas qwen-image-2.1-turbo`); never sent, only read from the server's defaults. */
    @SerialName("custom_sigmas") val customSigmas: List<Float>? = null,
)

@Serializable
data class SdCppGuidance(@SerialName("txt_cfg") val txtCfg: Float? = null)

@Serializable
data class SdCppLimits(
    @SerialName("min_width") val minWidth: Int? = null,
    @SerialName("max_width") val maxWidth: Int? = null,
    @SerialName("min_height") val minHeight: Int? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    @SerialName("max_batch_count") val maxBatchCount: Int? = null,
    @SerialName("max_queue_size") val maxQueueSize: Int? = null,
)

/** `POST /sdcpp/v1/img_gen` body. Null fields are omitted so the server's defaults apply. */
@Serializable
data class SdCppImgGenRequest(
    val prompt: String,
    @SerialName("negative_prompt") val negativePrompt: String? = null,
    val width: Int,
    val height: Int,
    /** Always sent: sd-server otherwise uses a fixed default seed (42). */
    val seed: Long,
    @SerialName("batch_count") val batchCount: Int? = null,
    @SerialName("sample_params") val sampleParams: SdCppSampleParams? = null,
    /** Input images for edits, as data URLs. */
    @SerialName("ref_images") val refImages: List<String>? = null,
    @SerialName("output_format") val outputFormat: String = "png",
    val preview: String? = null,
    @SerialName("preview_interval") val previewInterval: Int? = null,
)

@Serializable
data class SdCppSubmitResponse(
    val id: String,
    val status: String? = null,
)

@Serializable
data class SdCppJob(
    val id: String,
    val status: String,
    /** Epoch milliseconds. */
    val created: Long? = null,
    val started: Long? = null,
    val completed: Long? = null,
    @SerialName("queue_position") val queuePosition: Int? = null,
    val preview: SdCppPreview? = null,
    val result: SdCppResult? = null,
    val error: SdCppError? = null,
)

@Serializable
data class SdCppPreview(
    val pass: Int? = null,
    val step: Int? = null,
    @SerialName("total_steps") val totalSteps: Int? = null,
    @SerialName("b64_json") val b64Json: String? = null,
)

@Serializable
data class SdCppResult(
    @SerialName("output_format") val outputFormat: String? = null,
    val images: List<SdCppImage> = emptyList(),
)

@Serializable
data class SdCppImage(val index: Int = 0, @SerialName("b64_json") val b64Json: String)

@Serializable
data class SdCppError(val code: String? = null, val message: String? = null)

// ---- OpenAI images API (/v1/images) ----

@Serializable
data class OpenAiImagesRequest(
    val model: String? = null,
    val prompt: String,
    val n: Int,
    /** "WxH". Always sent: some servers default to very large sizes or don't understand "auto". */
    val size: String,
    /** Required for servers that default to URLs (LocalAI). */
    @SerialName("response_format") val responseFormat: String = "b64_json",
    @SerialName("output_format") val outputFormat: String = "png",
)

@Serializable
data class OpenAiImagesResponse(
    @SerialName("output_format") val outputFormat: String? = null,
    val data: List<OpenAiImageData> = emptyList(),
)

@Serializable
data class OpenAiImageData(
    @SerialName("b64_json") val b64Json: String? = null,
    val url: String? = null,
)
