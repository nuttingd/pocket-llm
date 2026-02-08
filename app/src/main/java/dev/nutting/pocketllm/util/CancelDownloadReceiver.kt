package dev.nutting.pocketllm.util

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dev.nutting.pocketllm.PocketLlmApplication
import dev.nutting.pocketllm.data.local.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CancelDownloadReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        WorkManager.getInstance(context).cancelUniqueWork("model_download_$modelId")

        if (notificationId != -1) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
        }

        val app = context.applicationContext as? PocketLlmApplication ?: return
        val localModelStore = app.container.localModelStore
        CoroutineScope(Dispatchers.IO).launch {
            localModelStore.updateStatus(modelId, DownloadStatus.NOT_DOWNLOADED)
        }
    }
}
