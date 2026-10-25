package dev.nutting.pocketllm.sd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the only sd.cpp context, in its own `:sd` process. One job at a time; the engine unloads after
 * an idle period so a multi-GB model does not sit in memory between prompts.
 */
class SdEngineService : Service(), SdProgressListener {

    companion object {
        private const val TAG = "SdEngineService"
        private const val CHANNEL_ID = "sd_engine"
        private const val NOTIFICATION_ID = 4101
        private const val IDLE_UNLOAD_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var engine: SdEngine? = null
    private var loadedPath: String? = null
    private var callback: ISdEngineCallback? = null
    private var currentJobId: String? = null
    private var idleJob: Job? = null
    private var lastStep = -1
    private var lastTotal = -1

    private val binder = object : ISdEngineService.Stub() {
        override fun load(manifestJson: String): Boolean = runBlocking {
            mutex.withLock {
                runCatching {
                    val manifest = json.decodeFromString(SdModelManifest.serializer(), manifestJson)
                    ensureLoaded(manifest.modelPath)
                    true
                }.getOrDefault(false)
            }
        }

        override fun unload() {
            scope.launch { mutex.withLock { unloadLocked() } }
        }

        override fun submit(jobId: String, requestJson: String, cb: ISdEngineCallback) {
            if (mutex.isLocked || currentJobId != null) {
                cb.onFinished(emptyArray(), "busy")
                return
            }
            callback = cb
            currentJobId = jobId
            idleJob?.cancel()
            startForeground(NOTIFICATION_ID, notification("Preparing model…"))
            val request = json.decodeFromString(SdJobRequest.serializer(), requestJson)
            scope.launch { runJob(request) }
        }

        override fun cancel(jobId: String) {
            if (currentJobId == jobId) engine?.cancel()
        }

        override fun engineCapabilities(): String = """{"vulkan":true}"""
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The client binds and calls submit(); this only satisfies the foreground-service start and
        // keeps the process alive while the client connects.
        startForeground(NOTIFICATION_ID, notification("Waiting for a job…"))
        scheduleIdleUnload()
        return START_NOT_STICKY
    }

    private suspend fun runJob(request: SdJobRequest) {
        mutex.withLock {
            try {
                ensureLoaded(request.modelPath)
                lastStep = -1
                lastTotal = -1
                val images = engine!!.generate(
                    prompt = request.prompt,
                    negativePrompt = request.negativePrompt,
                    width = request.width,
                    height = request.height,
                    steps = request.steps,
                    cfg = request.cfg,
                    seed = request.seed,
                    count = request.count,
                    outDir = File(request.outDir),
                    samplerIndex = engine!!.samplerIndex(request.sampler),
                )
                callback?.onFinished(images.map { it.absolutePath }.toTypedArray(), null)
            } catch (e: Throwable) {
                Log.e(TAG, "Generation failed", e)
                callback?.onFinished(emptyArray(), e.message ?: e::class.simpleName)
            } finally {
                currentJobId = null
                callback = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                scheduleIdleUnload()
            }
        }
    }

    private fun ensureLoaded(modelPath: String) {
        if (loadedPath == modelPath && engine != null) return
        unloadLocked()
        val e = SdEngine(this)
        if (!e.load(modelPath)) {
            e.unload()
            throw IllegalStateException("Could not load $modelPath")
        }
        engine = e
        loadedPath = modelPath
    }

    private fun unloadLocked() {
        engine?.unload()
        engine = null
        loadedPath = null
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                unloadLocked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onProgress(step: Int, totalSteps: Int, elapsedMs: Int) {
        if (step == lastStep && totalSteps == lastTotal) return
        lastStep = step
        lastTotal = totalSteps
        callback?.onProgress(step, totalSteps, elapsedMs)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("Generating… $step/$totalSteps"))
    }

    override fun onDestroy() {
        unloadLocked()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Image generation", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Generating image")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
    }
}
