package dev.nutting.pocketllm.domain.image

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nutting.pocketllm.PocketLlmApplication

/**
 * Runs one image job in the background, as a foreground service so a multi-minute generation survives the
 * app leaving the screen. WorkManager re-runs it if the process dies, and [ImageJobRunner] resumes from the
 * job's stored state.
 */
class ImageGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val container = (applicationContext as PocketLlmApplication).container
        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            // Android 12+ refuses to start a foreground service when WorkManager restarts us in the background
            // (e.g. after the process was killed). The job still runs while the process lives.
            Log.w(TAG, "Couldn't run image job ${jobId.take(8)} in the foreground; continuing in the background", e)
        }
        container.imageJobRunner.run(jobId)
        // The job row records the outcome; WorkManager has nothing to retry
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ImageNotifications.foregroundInfo(applicationContext)

    companion object {
        private const val TAG = "ImageGenerationWorker"
        const val KEY_JOB_ID = "job_id"

        fun workName(jobId: String) = "image-job-$jobId"
    }
}

/** Schedules image jobs on WorkManager: one unique work per job, needing a network connection. */
class WorkManagerImageJobScheduler(private val context: Context) : ImageJobScheduler {

    override fun enqueue(jobId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<ImageGenerationWorker>()
            .setInputData(workDataOf(ImageGenerationWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(ImageGenerationWorker.workName(jobId), policy, request)
    }

    override fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(ImageGenerationWorker.workName(jobId))
    }
}
