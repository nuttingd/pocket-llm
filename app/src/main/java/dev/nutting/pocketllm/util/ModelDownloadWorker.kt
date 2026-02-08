package dev.nutting.pocketllm.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nutting.pocketllm.MainActivity
import dev.nutting.pocketllm.PocketLlmApplication
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.ui.modelmanagement.ModelManagementViewModel
import java.io.File

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "ModelDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_URL = "model_url"
        const val KEY_PROJECTOR_URL = "projector_url"
        const val KEY_MODEL_FILE_NAME = "model_file_name"
        const val KEY_PROJECTOR_FILE_NAME = "projector_file_name"
        const val KEY_MODEL_SIZE_BYTES = "model_size_bytes"
        const val KEY_PROJECTOR_SIZE_BYTES = "projector_size_bytes"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL_SIZE = "total_size"
        const val KEY_BYTES_PER_SEC = "bytes_per_sec"
        const val KEY_NOTIFICATION_ID = "notification_id"
        private const val DEFAULT_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val RATE_SAMPLE_WINDOW_MS = 2000L
        private const val MAX_RETRIES = 3
    }

    private val notificationId = inputData.getInt(KEY_NOTIFICATION_ID, DEFAULT_NOTIFICATION_ID)
    private var lastNotificationUpdateMs = 0L
    private var rateSampleBytes = 0L
    private var rateSampleTimeMs = 0L
    private var currentBytesPerSec = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0)
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val modelUrl = inputData.getString(KEY_MODEL_URL) ?: return Result.failure()
        val projectorUrl = inputData.getString(KEY_PROJECTOR_URL) ?: return Result.failure()
        val modelFileName = inputData.getString(KEY_MODEL_FILE_NAME) ?: return Result.failure()
        val projectorFileName = inputData.getString(KEY_PROJECTOR_FILE_NAME) ?: return Result.failure()
        val modelSizeBytes = inputData.getLong(KEY_MODEL_SIZE_BYTES, 0)
        val projectorSizeBytes = inputData.getLong(KEY_PROJECTOR_SIZE_BYTES, 0)
        val totalSize = modelSizeBytes + projectorSizeBytes

        val app = applicationContext as PocketLlmApplication
        val localModelStore = app.container.localModelStore
        val settingsDataStore = app.container.settingsDataStore
        val modelsDir = app.container.modelsDir

        val downloader = ModelDownloader()

        try {
            setForeground(createForegroundInfo(0, totalSize))
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground service", e)
        }

        try {
            val modelFile = File(modelsDir, modelFileName)
            val projectorFile = File(modelsDir, projectorFileName)

            // Download model file
            downloader.download(modelUrl, modelFile).collect { progress ->
                val downloaded = progress.bytesDownloaded
                val totalProgress = downloaded.toFloat() / totalSize
                updateRate(downloaded)
                setProgress(workDataOf(KEY_PROGRESS to totalProgress, KEY_BYTES_PER_SEC to currentBytesPerSec))
                localModelStore.updateStatus(modelId, DownloadStatus.DOWNLOADING, downloaded)
                updateNotificationThrottled(downloaded, totalSize)
            }

            // Reset rate tracking for projector phase
            rateSampleBytes = 0L
            rateSampleTimeMs = 0L

            // Download projector file
            downloader.download(projectorUrl, projectorFile).collect { progress ->
                val downloaded = modelSizeBytes + progress.bytesDownloaded
                val totalProgress = downloaded.toFloat() / totalSize
                updateRate(downloaded)
                setProgress(workDataOf(KEY_PROGRESS to totalProgress, KEY_BYTES_PER_SEC to currentBytesPerSec))
                localModelStore.updateStatus(modelId, DownloadStatus.DOWNLOADING, downloaded)
                updateNotificationThrottled(downloaded, totalSize)
            }

            // Validate GGUF magic number
            if (ModelManagementViewModel.isValidGguf(modelFile) &&
                ModelManagementViewModel.isValidGguf(projectorFile)
            ) {
                localModelStore.updateStatus(modelId, DownloadStatus.COMPLETE, totalSize)
                settingsDataStore.setActiveLocalModelId(modelId)
                settingsDataStore.setInferenceProviderType("local")
                Log.i(TAG, "Model download complete: $modelFileName")
                postCompletionNotification(modelFileName, success = true)
                return Result.success(workDataOf(KEY_PROGRESS to 1.0f))
            } else {
                Log.e(TAG, "GGUF validation failed for $modelFileName")
                modelFile.delete()
                projectorFile.delete()
                localModelStore.updateStatus(modelId, DownloadStatus.FAILED)
                postCompletionNotification(modelFileName, success = false)
                return Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelFileName", e)
            if (runAttemptCount < MAX_RETRIES) {
                Log.i(TAG, "Will retry (attempt ${runAttemptCount + 1}/$MAX_RETRIES)")
                return Result.retry()
            }
            localModelStore.updateStatus(modelId, DownloadStatus.FAILED)
            postCompletionNotification(modelFileName, success = false)
            return Result.failure()
        }
    }

    private fun updateRate(totalDownloaded: Long) {
        val now = System.currentTimeMillis()
        if (rateSampleTimeMs == 0L) {
            rateSampleTimeMs = now
            rateSampleBytes = totalDownloaded
            return
        }
        val elapsed = now - rateSampleTimeMs
        if (elapsed >= RATE_SAMPLE_WINDOW_MS) {
            val deltaBytes = totalDownloaded - rateSampleBytes
            currentBytesPerSec = if (elapsed > 0) (deltaBytes * 1000L) / elapsed else 0L
            rateSampleTimeMs = now
            rateSampleBytes = totalDownloaded
        }
    }

    private fun createForegroundInfo(downloadedBytes: Long, totalBytes: Long): ForegroundInfo {
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val modelId = inputData.getString(KEY_MODEL_ID) ?: ""
        val cancelIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId,
            Intent(applicationContext, CancelDownloadReceiver::class.java).apply {
                putExtra(CancelDownloadReceiver.EXTRA_MODEL_ID, modelId)
                putExtra(CancelDownloadReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            PocketLlmApplication.CHANNEL_MODEL_DOWNLOAD,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (totalBytes > 0) {
                    val percent = ((downloadedBytes.toFloat() / totalBytes) * 100).toInt()
                    setProgress(100, percent, false)
                    val downloadedMb = downloadedBytes / (1024 * 1024)
                    val totalMb = totalBytes / (1024 * 1024)
                    val rateText = formatRate(currentBytesPerSec)
                    setContentText("${downloadedMb}MB / ${totalMb}MB  •  $rateText")
                } else {
                    setProgress(0, 0, true)
                    setContentText("Starting download…")
                }
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private suspend fun updateNotificationThrottled(downloadedBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            lastNotificationUpdateMs = now
            try {
                setForeground(createForegroundInfo(downloadedBytes, totalBytes))
            } catch (_: Exception) {
                // Foreground may not be available; fall through silently
            }
        }
    }

    private fun formatRate(bytesPerSec: Long): String {
        return when {
            bytesPerSec <= 0 -> "—"
            bytesPerSec < 1024 * 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
        }
    }

    private fun postCompletionNotification(modelFileName: String, success: Boolean) {
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(
            applicationContext,
            PocketLlmApplication.CHANNEL_MODEL_DOWNLOAD,
        )
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(if (success) "Model downloaded" else "Download failed")
            .setContentText(
                if (success) "$modelFileName is ready to use"
                else "Failed to download $modelFileName",
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
