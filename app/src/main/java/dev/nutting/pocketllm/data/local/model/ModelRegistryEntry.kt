package dev.nutting.pocketllm.data.local.model

data class ModelRegistryEntry(
    val id: String,
    val name: String,
    val description: String,
    val parameterCount: String,
    val quantization: String,
    val modelDownloadUrl: String,
    val projectorDownloadUrl: String,
    val modelFileName: String,
    val projectorFileName: String,
    val modelSizeBytes: Long,
    val projectorSizeBytes: Long,
    val minimumRamMb: Int,
) {
    val totalSizeBytes: Long get() = modelSizeBytes + projectorSizeBytes
}
