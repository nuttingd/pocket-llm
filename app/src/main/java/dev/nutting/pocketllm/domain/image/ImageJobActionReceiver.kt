package dev.nutting.pocketllm.domain.image

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.nutting.pocketllm.PocketLlmApplication
import kotlinx.coroutines.launch

/** Handles the Cancel action on an image job's ongoing notification. */
class ImageJobActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val container = (context.applicationContext as PocketLlmApplication).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                // Cancels locally right away; the server cancel it may start outlives this receiver
                container.imageJobLauncher.cancel(jobId)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't cancel image job ${jobId.take(8)} from the notification", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ImageJobActionReceiver"
        const val ACTION_CANCEL = "dev.nutting.pocketllm.action.CANCEL_IMAGE_JOB"
        const val EXTRA_JOB_ID = "job_id"
    }
}
