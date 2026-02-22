package dev.nutting.pocketllm.data.local.model

import kotlinx.serialization.Serializable

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
    val description: String = "",
    val parameterCount: String,
    val quantization: String,
    val modelFileName: String,
    val modelSizeBytes: Long,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val sourceUrl: String? = null,
    val isImported: Boolean = false,
)
