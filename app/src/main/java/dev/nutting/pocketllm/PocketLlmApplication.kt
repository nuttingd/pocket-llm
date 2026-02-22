package dev.nutting.pocketllm

import android.app.Application
import android.content.ComponentCallbacks2

class PocketLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Unload local LLM on memory pressure to prevent OOM
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= TRIM_MEMORY_RUNNING_LOW) {
                    container.localLlmClient.unload()
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() {
                container.localLlmClient.unload()
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
    }
}
