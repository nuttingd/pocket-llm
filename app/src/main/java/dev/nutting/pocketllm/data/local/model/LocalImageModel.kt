package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.Serializable

enum class ImageModelFamily { SD15, SDXL }

enum class ImageComponentRole { DIFFUSION, VAE, CLIP_L, CLIP_G, T5XXL }

@Serializable
data class ImageModelComponent(
    val role: ImageComponentRole,
    val fileName: String,
    val sizeBytes: Long,
    val sourceUrl: String? = null,
)

@Serializable
data class ImageModelCapabilities(
    val maxWidth: Int = 1024,
    val maxHeight: Int = 1024,
    val samplers: List<String> = listOf("euler", "euler_a", "dpm++2m"),
    val supportsImg2Img: Boolean = true,
    val supportsNegativePrompt: Boolean = true,
    val defaultSteps: Int = 20,
    val defaultCfg: Float = 7f,
)

/**
 * One on-device diffusion model. Manifest-shaped from the start so Flux/Qwen-era multi-file bundles
 * (VAE / CLIP / T5 components) can be added without changing storage, downloads or the picker.
 */
@Serializable
data class LocalImageModel(
    val id: String,
    val name: String,
    val family: ImageModelFamily,
    val components: List<ImageModelComponent>,
    val quantization: String,
    val minRamMb: Int,
    val capabilities: ImageModelCapabilities = ImageModelCapabilities(),
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val repoId: String? = null,
    val revision: String? = null,
    val isImported: Boolean = false,
    val errorMessage: String? = null,
) {
    val totalSizeBytes: Long get() = components.sumOf { it.sizeBytes }

    /** The checkpoint file the engine loads; v1 has exactly one DIFFUSION component. */
    val diffusionFile: String?
        get() = components.firstOrNull { it.role == ImageComponentRole.DIFFUSION }?.fileName

    fun fitsIn(deviceRamMb: Int): Boolean = minRamMb <= deviceRamMb
}
