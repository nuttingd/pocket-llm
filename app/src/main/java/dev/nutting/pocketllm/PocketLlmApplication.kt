package dev.nutting.pocketllm

import android.app.Application
import android.content.ComponentCallbacks2

class PocketLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Unload local LLM on memory pressure to prevent OOM. These callbacks run on the main thread, so
        // releaseMemory() unloads in the background. UI_HIDDEN (screen lock / app switch) is ignored so the
        // model stays loaded and an in-progress reply can finish.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                when {
                    level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE ->
                        container.localLlmClient.releaseMemory(cancelInFlight = true)
                    level >= TRIM_MEMORY_BACKGROUND ->
                        container.localLlmClient.releaseMemory(cancelInFlight = false)
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() {
                container.localLlmClient.releaseMemory(cancelInFlight = true)
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        })
    }
}
