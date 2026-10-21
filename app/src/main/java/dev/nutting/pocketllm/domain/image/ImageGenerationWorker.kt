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
import dev.nutting.pocketllm.data.local.dao.ImageJobDao
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Runs one image job in the background, as a foreground service so a multi-minute generation survives the
 * app leaving the screen. WorkManager re-runs it if the process dies, and [ImageJobRunner] resumes from the
 * job's stored state.
 */
class ImageGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val container = (applicationContext as PocketLlmApplication).container
        ImageJobSession(
            jobs = container.imageJobDao,
            run = container.imageJobRunner::run,
            showProgress = { job -> setForeground(ImageNotifications.foregroundInfo(applicationContext, job)) },
            announce = { job -> ImageNotifications.notifyFinished(applicationContext, job) },
        ).execute(jobId)
        // The job row records the outcome; WorkManager has nothing to retry
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val job = inputData.getString(KEY_JOB_ID)?.let { (applicationContext as PocketLlmApplication).container.imageJobDao.getById(it) }
        return ImageNotifications.foregroundInfo(applicationContext, job)
    }

    companion object {
        const val KEY_JOB_ID = "job_id"

        fun workName(jobId: String) = "image-job-$jobId"
    }
}

/**
 * Runs a job while keeping its ongoing notification in step with the job row, then announces the outcome.
 * Android 12+ refuses to start a foreground service when WorkManager restarts work in the background (e.g. after
 * the process was killed); the job then still runs while the process lives, just without the notification.
 */
internal class ImageJobSession(
    private val jobs: ImageJobDao,
    private val run: suspend (jobId: String) -> Unit,
    /** Shows or updates the ongoing notification; throws [IllegalStateException] when foreground isn't allowed. */
    private val showProgress: suspend (ImageJobEntity) -> Unit,
    private val announce: (ImageJobEntity) -> Unit,
) {
    suspend fun execute(jobId: String) = coroutineScope {
        val job = jobs.getById(jobId) ?: return@coroutineScope
        if (job.status.isTerminal) return@coroutineScope

        val updates = if (tryShow(job)) {
            launch {
                jobs.observeById(jobId)
                    .filterNotNull()
                    .takeWhile { !it.status.isTerminal }
                    .distinctUntilChangedBy { listOf(it.status, it.queuePosition, it.pass, it.step, it.totalSteps, it.startedAt) }
                    .collect { tryShow(it) }
            }
        } else {
            null
        }
        try {
            run(jobId)
        } finally {
            updates?.cancel()
        }
        jobs.getById(jobId)?.takeIf { it.status.isTerminal }?.let(announce)
    }

    private suspend fun tryShow(job: ImageJobEntity): Boolean = try {
        showProgress(job)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Couldn't run image job ${job.id.take(8)} in the foreground; continuing in the background", e)
        false
    }

    private companion object {
        const val TAG = "ImageGenerationWorker"
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
