package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
}

@Serializable
data class LocalModel(
    val id: String,
    val name: String,
    val parameterCount: String,
    val quantization: String,
    val modelFileName: String,
    val projectorFileName: String = "",
    val modelSizeBytes: Long,
    val projectorSizeBytes: Long = 0L,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val sourceUrl: String? = null,
    val projectorSourceUrl: String? = null,
    val isImported: Boolean = false,
    val minimumRamMb: Int = 4096,
    val contextWindowSize: Int = 4096,
) {
    val totalSizeBytes: Long get() = modelSizeBytes + projectorSizeBytes
}
