package dev.nutting.pocketllm

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PocketLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        sweepOrphanedImages()
        resumeImageJobs()

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

    /**
     * Makes sure every unfinished image job has its background work. WorkManager normally keeps it across
     * restarts; this covers work lost some other way, so no reply stays pending forever. KEEP leaves
     * existing work alone.
     */
    private fun resumeImageJobs() {
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.imageJobDao.getActive().forEach { container.imageJobScheduler.enqueue(it.id) }
            } catch (e: Exception) {
                Log.e("PocketLlmApplication", "Couldn't resume unfinished image jobs", e)
            }
        }
    }

    /** Removes image files left behind by deleted messages or interrupted saves. */
    private fun sweepOrphanedImages() {
        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.imageStore.sweepOrphans(container.generatedImageDao.getAllFilePaths().toSet())
            } catch (e: Exception) {
                Log.w("PocketLlmApplication", "Image orphan sweep failed; it will run again next start", e)
            }
        }
    }
}
