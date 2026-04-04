package dev.nutting.pocketllm.data.local.model

data class ModelRegistryEntry(
    val id: String,
    val name: String,
    val description: String,
    val parameterCount: String,
    val quantization: String,
    val modelDownloadUrl: String,
    val modelFileName: String,
    val modelSizeBytes: Long,
    val minimumRamMb: Int,
    val contextWindowSize: Int = 4096,
    val projectorDownloadUrl: String? = null,
    val projectorFileName: String? = null,
    val projectorSizeBytes: Long = 0L,
) {
    val totalSizeBytes: Long get() = modelSizeBytes + projectorSizeBytes
    val hasProjector: Boolean get() = projectorDownloadUrl != null
}
