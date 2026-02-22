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
)
