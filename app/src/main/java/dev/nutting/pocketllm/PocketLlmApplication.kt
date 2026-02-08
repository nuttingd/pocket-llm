package dev.nutting.pocketllm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PocketLlmApplication : Application() {

    companion object {
        const val CHANNEL_MODEL_DOWNLOAD = "model_download"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MODEL_DOWNLOAD,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress notifications for model downloads"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
