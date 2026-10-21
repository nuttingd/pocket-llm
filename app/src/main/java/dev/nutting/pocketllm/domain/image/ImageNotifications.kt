package dev.nutting.pocketllm.domain.image

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dev.nutting.pocketllm.MainActivity
import dev.nutting.pocketllm.data.local.entity.ImageJobEntity
import dev.nutting.pocketllm.data.local.entity.ImageJobKind
import dev.nutting.pocketllm.data.local.entity.ImageJobStatus

/**
 * Notifications for background image generation: an ongoing one per running job (status, elapsed time, step
 * progress and Cancel), and a result one when a job finishes while the app isn't on screen. Each job has its own
 * notification id, so two jobs never overwrite each other. Tapping either opens the job's conversation.
 */
object ImageNotifications {
    const val CHANNEL_ID = "image_generation"
    const val RESULT_CHANNEL_ID = "image_results"

    /** The ongoing notification while [job] runs, as the worker's foreground info. */
    fun foregroundInfo(context: Context, job: ImageJobEntity?): ForegroundInfo {
        ensureChannels(context)
        val notification = ongoing(context, job)
        val id = job?.let { ongoingId(it.id) } ?: ONGOING_FALLBACK_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun ongoing(context: Context, job: ImageJobEntity?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (job == null) return builder.setContentTitle("Generating image…").setProgress(0, 0, true).build()

        val (title, text, progress) = ongoingContent(job)
        builder.setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openConversation(context, job))
            .setWhen(job.startedAt ?: job.createdAt)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .addAction(0, "Cancel", cancelAction(context, job.id))
        if (progress != null) builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    /** Posts "Image ready" or "Couldn't generate the image" for a job that finished while the app was away. */
    fun notifyFinished(context: Context, job: ImageJobEntity) {
        val content = resultContent(job) ?: return
        if (isAppInForeground(context) || !canNotify(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(content.first)
            .setContentText(content.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.second))
            .setContentIntent(openConversation(context, job))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(resultId(job.id), notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post
        }
    }

    /** Title, text and progress fraction (null = indeterminate) for the ongoing notification. */
    internal fun ongoingContent(job: ImageJobEntity): Triple<String, String, Float?> {
        val status = describeImageJob(job)
        val title = if (job.kind == ImageJobKind.EDIT) "Editing image" else "Generating image"
        val text = listOfNotNull(status.phase, status.detail).joinToString(" · ") + "\n" + job.prompt
        return Triple(title, text, status.progress)
    }

    /** Title and text of the result notification, or null when the job's outcome isn't worth a notification. */
    internal fun resultContent(job: ImageJobEntity): Pair<String, String>? = when (job.status) {
        ImageJobStatus.SUCCEEDED -> "Image ready" to job.prompt
        ImageJobStatus.FAILED -> "Couldn't generate the image" to (job.errorMessage ?: job.prompt)
        else -> null // Cancelled by the user, or not finished
    }

    private fun openConversation(context: Context, job: ImageJobEntity): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, job.conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, job.conversationId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAction(context: Context, jobId: String): PendingIntent {
        val intent = Intent(context, ImageJobActionReceiver::class.java)
            .setAction(ImageJobActionReceiver.ACTION_CANCEL)
            .putExtra(ImageJobActionReceiver.EXTRA_JOB_ID, jobId)
        return PendingIntent.getBroadcast(
            context, jobId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** The reply already shows the result when the app is on screen, so a notification would only be noise. */
    private fun isAppInForeground(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Image generation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of images being generated on your image server"
                }
            )
        }
        if (manager.getNotificationChannel(RESULT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Generated images", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When an image finishes or fails while you're away from the app"
                }
            )
        }
    }

    private const val PROGRESS_MAX = 100
    private const val ONGOING_FALLBACK_ID = 2_001

    // Distinct ranges keep a job's ongoing and result notifications (and the model download's) apart
    internal fun ongoingId(jobId: String) = 0x2000_0000 or (jobId.hashCode() and 0x0FFF_FFFF)
    internal fun resultId(jobId: String) = 0x3000_0000 or (jobId.hashCode() and 0x0FFF_FFFF)
}
