package dev.nutting.pocketllm.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nutting.pocketllm.PocketLlmApplication
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import java.io.File
import java.io.RandomAccessFile

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_URL = "model_url"
        const val KEY_MODEL_FILENAME = "model_filename"
        const val KEY_TOTAL_SIZE = "total_size"
        const val KEY_PROGRESS_BYTES = "progress_bytes"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 42

        private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

        fun isValidGguf(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = raf.readInt().let { Integer.reverseBytes(it) }
                    magic == GGUF_MAGIC
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val downloader = ModelDownloader()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val modelUrl = inputData.getString(KEY_MODEL_URL) ?: return Result.failure()
        val modelFilename = inputData.getString(KEY_MODEL_FILENAME) ?: return Result.failure()
        val totalSize = inputData.getLong(KEY_TOTAL_SIZE, 0L)

        val container = (applicationContext as PocketLlmApplication).container
        val localModelStore = container.localModelStore
        val modelsDir = container.modelsDir

        createNotificationChannel()

        val modelFile = File(modelsDir, modelFilename)

        try {
            setForeground(createForegroundInfo("Downloading model...", 0, totalSize))

            // Download model file
            downloader.download(modelUrl, modelFile).collect { progress ->
                localModelStore.updateStatus(modelId, DownloadStatus.DOWNLOADING, progress.bytesDownloaded)
                setForeground(createForegroundInfo("Downloading model...", progress.bytesDownloaded, progress.totalBytes))
                setProgress(workDataOf(
                    KEY_PROGRESS_BYTES to progress.bytesDownloaded,
                    KEY_PROGRESS_TOTAL to progress.totalBytes,
                ))
            }

            // Validate GGUF magic number
            if (!isValidGguf(modelFile)) {
                Log.e(TAG, "Downloaded file is not a valid GGUF")
                modelFile.delete()
                localModelStore.updateStatus(modelId, DownloadStatus.FAILED)
                return Result.failure()
            }

            // Mark complete and set as active
            localModelStore.updateStatus(modelId, DownloadStatus.COMPLETE, modelFile.length())
            localModelStore.setActiveModelId(modelId)
            Log.i(TAG, "Model $modelId downloaded successfully")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelId", e)
            localModelStore.updateStatus(modelId, DownloadStatus.FAILED)
            return Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress notifications for model downloads"
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(title: String, downloaded: Long, total: Long): ForegroundInfo {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val downloadedMb = downloaded / (1024 * 1024)
        val totalMb = total / (1024 * 1024)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("${downloadedMb}MB / ${totalMb}MB")
            .setProgress(100, progress, total <= 0)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
