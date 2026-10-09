package dev.nutting.pocketllm.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import dev.nutting.pocketllm.data.local.model.LocalModel
import dev.nutting.pocketllm.data.local.model.LocalModelStore
import dev.nutting.pocketllm.data.local.model.ModelRegistryEntry
import java.io.File

/**
 * Starts, retries and cancels background model downloads. Shared by the curated model list and
 * the Hugging Face browser so both go through the same storage/network checks.
 */
class ModelDownloadManager(
    private val context: Context,
    private val localModelStore: LocalModelStore,
    private val modelsDir: File,
) {

    sealed interface Precheck {
        data object Ok : Precheck
        data object CellularNetwork : Precheck
        data class InsufficientStorage(val message: String) : Precheck
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val STORAGE_BUFFER_BYTES = 100_000_000L // 100 MB

        private fun workName(modelId: String) = "download_$modelId"
    }

    /** Checks free space and network type before a download is started. */
    fun precheck(totalSizeBytes: Long): Precheck {
        val availableBytes = StatFs(modelsDir.absolutePath).availableBytes
        val requiredBytes = totalSizeBytes + STORAGE_BUFFER_BYTES
        if (availableBytes < requiredBytes) {
            return Precheck.InsufficientStorage(
                "Not enough storage. Need ${requiredBytes / (1024 * 1024)}MB, have ${availableBytes / (1024 * 1024)}MB."
            )
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Precheck.CellularNetwork
        }
        return Precheck.Ok
    }

    suspend fun start(entry: ModelRegistryEntry) {
        val model = LocalModel(
            id = entry.id,
            name = entry.name,
            parameterCount = entry.parameterCount,
            quantization = entry.quantization,
            modelFileName = entry.modelFileName,
            projectorFileName = entry.projectorFileName ?: "",
            modelSizeBytes = entry.modelSizeBytes,
            projectorSizeBytes = entry.projectorSizeBytes,
            downloadStatus = DownloadStatus.DOWNLOADING,
            sourceUrl = entry.modelDownloadUrl,
            projectorSourceUrl = entry.projectorDownloadUrl,
            minimumRamMb = entry.minimumRamMb,
            contextWindowSize = entry.contextWindowSize,
        )
        localModelStore.save(model)
        enqueue(model, ExistingWorkPolicy.KEEP)
        Log.i(TAG, "Download enqueued for ${entry.id}")
    }

    /** Re-enqueues a failed download using the URLs stored with the model. Returns false if it has none. */
    suspend fun retry(modelId: String): Boolean {
        val model = localModelStore.getById(modelId) ?: return false
        if (model.sourceUrl.isNullOrEmpty()) return false
        localModelStore.updateStatus(modelId, DownloadStatus.DOWNLOADING, model.downloadedBytes)
        enqueue(model, ExistingWorkPolicy.REPLACE)
        Log.i(TAG, "Retry enqueued for $modelId")
        return true
    }

    suspend fun cancel(modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(modelId))
        localModelStore.updateStatus(modelId, DownloadStatus.FAILED, errorMessage = "Download cancelled")
    }

    private fun enqueue(model: LocalModel, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_ID to model.id,
                ModelDownloadWorker.KEY_MODEL_URL to model.sourceUrl,
                ModelDownloadWorker.KEY_MODEL_FILENAME to model.modelFileName,
                ModelDownloadWorker.KEY_TOTAL_SIZE to model.totalSizeBytes,
                ModelDownloadWorker.KEY_PROJECTOR_URL to model.projectorSourceUrl,
                ModelDownloadWorker.KEY_PROJECTOR_FILENAME to model.projectorFileName.ifEmpty { null },
                ModelDownloadWorker.KEY_PROJECTOR_SIZE to model.projectorSizeBytes,
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(model.id), policy, request)
    }
}
